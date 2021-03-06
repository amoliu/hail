package is.hail.expr.ir

import is.hail.HailContext
import is.hail.annotations._
import is.hail.annotations.aggregators.RegionValueAggregator
import is.hail.expr.types._
import is.hail.expr.types.physical.PStruct
import is.hail.expr.ir
import is.hail.io.bgen.MatrixBGENReader
import is.hail.io.vcf.MatrixVCFReader
import is.hail.rvd._
import is.hail.sparkextras.ContextRDD
import is.hail.table.TableSpec
import is.hail.utils._
import is.hail.variant._
import org.apache.spark.sql.Row
import org.json4s._

import scala.collection.mutable

object MatrixIR {
  def read(hc: HailContext, path: String, dropCols: Boolean = false, dropRows: Boolean = false, requestedType: Option[MatrixType]): MatrixIR = {
    val reader = MatrixNativeReader(path)
    MatrixRead(requestedType.getOrElse(reader.fullType), dropCols, dropRows, reader)
  }

  def range(hc: HailContext, nRows: Int, nCols: Int, nPartitions: Option[Int], dropCols: Boolean = false, dropRows: Boolean = false): MatrixIR = {
    val reader = MatrixRangeReader(nRows, nCols, nPartitions)
    MatrixRead(reader.fullType, dropCols = dropCols, dropRows = dropRows, reader = reader)
  }

  def chooseColsWithArray(typ: MatrixType): (MatrixType, (MatrixValue, Array[Int]) => MatrixValue) = {
    val rowType = typ.rvRowType
    val keepType = TArray(+TInt32())
    val (rTyp, makeF) = ir.Compile[Long, Long, Long]("row", rowType,
      "keep", keepType,
      body = InsertFields(ir.Ref("row", rowType), Seq((MatrixType.entriesIdentifier,
        ir.ArrayMap(ir.Ref("keep", keepType), "i",
          ir.ArrayRef(ir.GetField(ir.In(0, rowType), MatrixType.entriesIdentifier),
            ir.Ref("i", TInt32())))))))
    assert(rTyp.isOfType(rowType))

    val newMatrixType = typ.copy(rvRowType = coerce[TStruct](rTyp))

    val keepF = { (mv: MatrixValue, keep: Array[Int]) =>
      val keepBc = mv.sparkContext.broadcast(keep)
      mv.copy(typ = newMatrixType,
        colValues = mv.colValues.copy(value = keep.map(mv.colValues.value)),
        rvd = mv.rvd.mapPartitionsWithIndex(newMatrixType.rvdType, { (i, ctx, it) =>
          val f = makeF(i)
          val keep = keepBc.value
          val rv2 = RegionValue()

          it.map { rv =>
            val region = ctx.region
            rv2.set(region,
              f(region, rv.offset, false, region.appendArrayInt(keep), false))
            rv2
          }
        }))
    }
    (newMatrixType, keepF)
  }

  def filterCols(typ: MatrixType): (MatrixType, (MatrixValue, (Annotation, Int) => Boolean) => MatrixValue) = {
    val (t, keepF) = chooseColsWithArray(typ)
    (t, { (mv: MatrixValue, p: (Annotation, Int) => Boolean) =>
      val keep = (0 until mv.nCols)
        .view
        .filter { i => p(mv.colValues.value(i), i) }
        .toArray
      keepF(mv, keep)
    })
  }

  def collectColsByKey(typ: MatrixType): (MatrixType, MatrixValue => MatrixValue) = {
    val oldRVRowType = typ.rvRowType
    val oldEntryArrayType = typ.entryArrayType
    val oldEntryType = typ.entryType

    val newColValueType = TStruct(typ.colValueStruct.fields.map(f => f.copy(typ = TArray(f.typ, required = true))))
    val newColType = typ.colKeyStruct ++ newColValueType
    val newEntryType = TStruct(typ.entryType.fields.map(f => f.copy(typ = TArray(f.typ, required = true))))
    val newMatrixType = typ.copyParts(colType = newColType, entryType = newEntryType)
    val newRVRowType = newMatrixType.rvRowType
    val localRowSize = newRVRowType.size

    (newMatrixType, { mv =>
      val colValMap: Map[Row, Array[Int]] = mv.colValues.value
        .map(_.asInstanceOf[Row])
        .zipWithIndex
        .groupBy[Row] { case (r, i) => typ.extractColKey(r) }
        .mapValues {
          _.map { case (r, i) => i }.toArray
        }
      val idxMap = colValMap.values.toArray

      val newColValues: BroadcastIndexedSeq = mv.colValues.copy(
        value = colValMap.map { case (key, idx) =>
          Row.fromSeq(key.toSeq ++ newColValueType.fields.map { f =>
            idx.map { i =>
              mv.colValues.value(i).asInstanceOf[Row]
                .get(typ.colValueFieldIdx(f.index))
            }.toFastIndexedSeq
          })
        }.toFastIndexedSeq)

      val newRVD = mv.rvd.mapPartitions(newMatrixType.rvdType) { it =>
        val rvb = new RegionValueBuilder()
        val rv2 = RegionValue()

        it.map { rv =>
          val entryArrayOffset = oldRVRowType.loadField(rv, oldRVRowType.fieldIdx(MatrixType.entriesIdentifier))

          rvb.set(rv.region)
          rvb.start(newRVRowType)
          rvb.startStruct()
          var i = 0
          while (i < localRowSize - 1) {
            rvb.addField(oldRVRowType, rv, i)
            i += 1
          }
          rvb.startArray(idxMap.length) // start entries array
          i = 0
          while (i < idxMap.length) {
            rvb.startStruct()
            var j = 0
            while (j < newEntryType.size) {
              rvb.startArray(idxMap(i).length)
              var k = 0
              while (k < idxMap(i).length) {
                rvb.addField(
                  oldEntryType,
                  rv.region,
                  oldEntryArrayType.loadElement(rv.region, entryArrayOffset, idxMap(i)(k)),
                  j
                )
                k += 1
              }
              rvb.endArray()
              j += 1
            }
            rvb.endStruct()
            i += 1
          }
          rvb.endArray()
          rvb.endStruct()
          rv2.set(rv.region, rvb.end())
          rv2
        }
      }

      mv.copy(
        typ = newMatrixType,
        colValues = newColValues,
        rvd = newRVD
      )
    })
  }
}

abstract sealed class MatrixIR extends BaseIR {
  def typ: MatrixType

  def partitionCounts: Option[IndexedSeq[Long]] = None

  def getOrComputePartitionCounts(): IndexedSeq[Long] = {
    partitionCounts
      .getOrElse(
        Optimize(
          TableMapRows(
            TableKeyBy(MatrixRowsTable(this), FastIndexedSeq()),
            MakeStruct(FastIndexedSeq())
          ))
          .execute(HailContext.get)
          .rvd
          .countPerPartition()
          .toFastIndexedSeq)
  }

  def columnCount: Option[Int] = None

  def execute(hc: HailContext): MatrixValue

  override def copy(newChildren: IndexedSeq[BaseIR]): MatrixIR
}

case class MatrixLiteral(value: MatrixValue) extends MatrixIR {
  val typ: MatrixType = value.typ

  def children: IndexedSeq[BaseIR] = Array.empty[BaseIR]

  def execute(hc: HailContext): MatrixValue = value

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixLiteral = {
    assert(newChildren.isEmpty)
    MatrixLiteral(value)
  }

  override def columnCount: Option[Int] = Some(value.nCols)

  override def toString: String = "MatrixLiteral(...)"
}

object MatrixReader {
  implicit val formats: Formats = RelationalSpec.formats + ShortTypeHints(
    List(classOf[MatrixNativeReader], classOf[MatrixRangeReader], classOf[MatrixVCFReader],
      classOf[MatrixBGENReader]))
}

abstract class MatrixReader {
  def apply(mr: MatrixRead): MatrixValue

  def columnCount: Option[Int]

  def partitionCounts: Option[IndexedSeq[Long]]

  def fullType: MatrixType
}

case class MatrixNativeReader(path: String) extends MatrixReader {

  val spec: MatrixTableSpec = (RelationalSpec.read(HailContext.get, path): @unchecked) match {
    case mts: MatrixTableSpec => mts
    case _: TableSpec => fatal(s"file is a Table, not a MatrixTable: '$path'")
  }

  lazy val columnCount: Option[Int] = Some(RelationalSpec.read(HailContext.get, path + "/cols")
    .asInstanceOf[TableSpec]
    .partitionCounts
    .sum
    .toInt)

  lazy val partitionCounts: Option[IndexedSeq[Long]] = Some(spec.partitionCounts)

  val fullType = spec.matrix_type

  def apply(mr: MatrixRead): MatrixValue = {
    val hc = HailContext.get

    val requestedType = mr.typ
    assert(PruneDeadFields.isSupertype(requestedType, spec.matrix_type))

    val globals = spec.globalsComponent.readLocal(hc, path, requestedType.globalType)(0).asInstanceOf[Row]

    val colAnnotations =
      if (mr.dropCols)
        FastIndexedSeq.empty[Annotation]
      else
        spec.colsComponent.readLocal(hc, path, requestedType.colType).asInstanceOf[IndexedSeq[Annotation]]

    val rvd =
      if (mr.dropRows)
        RVD.empty(hc.sc, requestedType.rvdType)
      else {
        val fullRowType = requestedType.rvRowType
        val localEntriesIndex = requestedType.entriesIdx

        val rowsRVD = spec.rowsComponent.read(hc, path, requestedType.rowType)
        if (mr.dropCols) {
          val (t2, makeF) = ir.Compile[Long, Long](
            "row", requestedType.rowType,
            MakeStruct(
              fullRowType.fields.zipWithIndex.map { case (f, i) =>
                val v: IR = if (i == localEntriesIndex)
                  MakeArray(FastSeq.empty, TArray(requestedType.entryType))
                else
                  GetField(Ref("row", requestedType.rowType), f.name)
                f.name -> v
              }))
          assert(t2 == fullRowType)

          rowsRVD.mapPartitionsWithIndex(requestedType.rvdType) { (i, it) =>
            val f = makeF(i)
            val rv2 = RegionValue()
            it.map { rv =>
              val off = f(rv.region, rv.offset, false)
              rv2.set(rv.region, off)
              rv2
            }
          }
        } else {
          val entriesRVD = spec.entriesComponent.read(hc, path, requestedType.entriesRVType)
          val entriesRowType = entriesRVD.rowType

          val (t2, makeF) = ir.Compile[Long, Long, Long](
            "row", requestedType.rowType,
            "entriesRow", entriesRowType,
            MakeStruct(
              fullRowType.fields.zipWithIndex.map { case (f, i) =>
                val v: IR = if (i == localEntriesIndex)
                  GetField(Ref("entriesRow", entriesRowType), MatrixType.entriesIdentifier)
                else
                  GetField(Ref("row", requestedType.rowType), f.name)
                f.name -> v
              }))
          assert(t2 == fullRowType)

          rowsRVD.zipPartitionsWithIndex(requestedType.rvdType, entriesRVD) { (i, ctx, it1, it2) =>
            val f = makeF(i)
            val region = ctx.region
            val rv3 = RegionValue(region)
            new Iterator[RegionValue] {
              def hasNext = {
                val hn1 = it1.hasNext
                val hn2 = it2.hasNext
                assert(hn1 == hn2)
                hn1
              }

              def next(): RegionValue = {
                val rv1 = it1.next()
                val rv2 = it2.next()
                val off = f(region, rv1.offset, false, rv2.offset, false)
                rv3.set(region, off)
                rv3
              }
            }
          }
        }
      }

    MatrixValue(
      requestedType,
      BroadcastRow(globals, requestedType.globalType, hc.sc),
      BroadcastIndexedSeq(colAnnotations, TArray(requestedType.colType), hc.sc),
      rvd)
  }
}

case class MatrixRangeReader(nRows: Int, nCols: Int, nPartitions: Option[Int]) extends MatrixReader {
  val fullType: MatrixType = MatrixType.fromParts(
    globalType = TStruct.empty(),
    colKey = Array("col_idx"),
    colType = TStruct("col_idx" -> TInt32()),
    rowKey = Array("row_idx"),
    rowType = TStruct("row_idx" -> TInt32()),
    entryType = TStruct.empty())

  val columnCount: Option[Int] = Some(nCols)

  lazy val partitionCounts: Option[IndexedSeq[Long]] = {
    val nPartitionsAdj = math.min(nRows, nPartitions.getOrElse(HailContext.get.sc.defaultParallelism))
    Some(partition(nRows, nPartitionsAdj).map(_.toLong))
  }

  def apply(mr: MatrixRead): MatrixValue = {
    assert(mr.typ == fullType)

    val partCounts = mr.partitionCounts.get.map(_.toInt)
    val nPartitionsAdj = mr.partitionCounts.get.length

    val hc = HailContext.get
    val localRVType = fullType.rvRowType
    val partStarts = partCounts.scanLeft(0)(_ + _)
    val localNCols = if (mr.dropCols) 0 else nCols

    val rvd = if (mr.dropRows)
      RVD.empty(hc.sc, fullType.rvdType)
    else {
      RVD(fullType.rvdType,
        new RVDPartitioner(
          fullType.rowKeyStruct,
          Array.tabulate(nPartitionsAdj) { i =>
            val start = partStarts(i)
            Interval(Row(start), Row(start + partCounts(i)), includesStart = true, includesEnd = false)
          }),
        ContextRDD.parallelize[RVDContext](hc.sc, Range(0, nPartitionsAdj), nPartitionsAdj)
          .cmapPartitionsWithIndex { (i, ctx, _) =>
            val region = ctx.region
            val rvb = ctx.rvb
            val rv = RegionValue(region)

            val start = partStarts(i)
            Iterator.range(start, start + partCounts(i))
              .map { j =>
                rvb.start(localRVType)
                rvb.startStruct()

                // row idx field
                rvb.addInt(j)

                // entries field
                rvb.startArray(localNCols)
                var i = 0
                while (i < localNCols) {
                  rvb.startStruct()
                  rvb.endStruct()
                  i += 1
                }
                rvb.endArray()

                rvb.endStruct()
                rv.setOffset(rvb.end())
                rv
              }
          })
    }

    MatrixValue(fullType,
      BroadcastRow(Row(), fullType.globalType, hc.sc),
      BroadcastIndexedSeq(
        Iterator.range(0, localNCols)
          .map(Row(_))
          .toFastIndexedSeq,
        TArray(fullType.colType),
        hc.sc),
      rvd)
  }
}

case class MatrixRead(
  typ: MatrixType,
  dropCols: Boolean,
  dropRows: Boolean,
  reader: MatrixReader) extends MatrixIR {

  def children: IndexedSeq[BaseIR] = Array.empty[BaseIR]

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixRead = {
    assert(newChildren.isEmpty)
    MatrixRead(typ, dropCols, dropRows, reader)
  }

  def execute(hc: HailContext): MatrixValue = {
    val mv = reader(this)
    assert(mv.typ == typ)
    mv
  }

  override def toString: String = s"MatrixRead($typ, " +
    s"partitionCounts = $partitionCounts, " +
    s"columnCount = $columnCount, " +
    s"dropCols = $dropCols, " +
    s"dropRows = $dropRows)"

  override def partitionCounts: Option[IndexedSeq[Long]] = {
    if (dropRows)
      Some(Array.empty[Long])
    else
      reader.partitionCounts
  }

  override def columnCount: Option[Int] = {
    if (dropCols)
      Some(0)
    else
      reader.columnCount
  }
}

case class MatrixFilterCols(child: MatrixIR, pred: IR) extends MatrixIR {

  def children: IndexedSeq[BaseIR] = Array(child, pred)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixFilterCols = {
    assert(newChildren.length == 2)
    MatrixFilterCols(newChildren(0).asInstanceOf[MatrixIR], newChildren(1).asInstanceOf[IR])
  }

  val (typ, filterF) = MatrixIR.filterCols(child.typ)

  override def partitionCounts: Option[IndexedSeq[Long]] = child.partitionCounts

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)

    val localGlobals = prev.globals.broadcast
    val localColType = typ.colType

    val (rTyp, predCompiledFunc) = ir.Compile[Long, Long, Boolean](
      "global", typ.globalType,
      "sa", typ.colType,
      pred)

    val predF = predCompiledFunc(0)
    val p = (sa: Annotation, i: Int) => {
      Region.scoped { colRegion =>
        // FIXME: it would be nice to only load the globals once per matrix
        val rvb = new RegionValueBuilder(colRegion)
        rvb.start(typ.globalType)
        rvb.addAnnotation(typ.globalType, localGlobals.value)
        val globalRVoffset = rvb.end()

        val colRVb = new RegionValueBuilder(colRegion)
        colRVb.start(localColType)
        colRVb.addAnnotation(localColType, sa)
        val colRVoffset = colRVb.end()
        predF(colRegion, globalRVoffset, false, colRVoffset, false)
      }
    }

    filterF(prev, p)
  }
}

case class MatrixFilterRows(child: MatrixIR, pred: IR) extends MatrixIR {

  def children: IndexedSeq[BaseIR] = Array(child, pred)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixFilterRows = {
    assert(newChildren.length == 2)
    MatrixFilterRows(newChildren(0).asInstanceOf[MatrixIR], newChildren(1).asInstanceOf[IR])
  }

  def typ: MatrixType = child.typ

  override def columnCount: Option[Int] = child.columnCount

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)
    assert(child.typ == prev.typ)

    if (pred == True())
      return prev
    else if (pred == False())
      return prev.copy(rvd = RVD.empty(hc.sc, prev.rvd.typ))

    val localGlobalsType = prev.typ.globalType
    val globalsBc = prev.globals.broadcast

    val vaType = prev.typ.rvRowType
    val (rTyp, f) = Compile[Long, Long, Boolean](
      "global", prev.typ.globalType,
      "va", vaType,
      pred)

    val filteredRDD = prev.rvd.mapPartitionsWithIndex(prev.typ.rvdType, { (i, ctx, it) =>
      val rvb = new RegionValueBuilder()
      val predicate = f(i)

      val partRegion = ctx.freshContext.region

      rvb.set(partRegion)
      rvb.start(localGlobalsType)
      rvb.addAnnotation(localGlobalsType, globalsBc.value)
      val globals = rvb.end()

      it.filter { rv =>
        val region = rv.region
        val row = rv.offset

        if (predicate(region, globals, false, row, false))
          true
        else {
          ctx.region.clear()
          false
        }
      }
    })

    prev.copy(rvd = filteredRDD)
  }
}

case class MatrixChooseCols(child: MatrixIR, oldIndices: IndexedSeq[Int]) extends MatrixIR {
  def children: IndexedSeq[BaseIR] = Array(child)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixChooseCols = {
    assert(newChildren.length == 1)
    MatrixChooseCols(newChildren(0).asInstanceOf[MatrixIR], oldIndices)
  }

  val (typ, colsF) = MatrixIR.chooseColsWithArray(child.typ)

  override def partitionCounts: Option[IndexedSeq[Long]] = child.partitionCounts

  override def columnCount: Option[Int] = Some(oldIndices.length)

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)

    colsF(prev, oldIndices.toArray)
  }
}

case class MatrixCollectColsByKey(child: MatrixIR) extends MatrixIR {
  def children: IndexedSeq[BaseIR] = Array(child)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixCollectColsByKey = {
    assert(newChildren.length == 1)
    MatrixCollectColsByKey(newChildren(0).asInstanceOf[MatrixIR])
  }

  val (typ, groupF) = MatrixIR.collectColsByKey(child.typ)

  override def partitionCounts: Option[IndexedSeq[Long]] = child.partitionCounts

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)
    groupF(prev)
  }
}

case class MatrixAggregateRowsByKey(child: MatrixIR, entryExpr: IR, rowExpr: IR) extends MatrixIR {
  require(child.typ.rowKey.nonEmpty)

  def children: IndexedSeq[BaseIR] = Array(child, entryExpr, rowExpr)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixAggregateRowsByKey = {
    val IndexedSeq(newChild: MatrixIR, newEntryExpr: IR, newRowExpr: IR) = newChildren
    MatrixAggregateRowsByKey(newChild, newEntryExpr, newRowExpr)
  }

  val typ: MatrixType = child.typ.copyParts(
    rowType = child.typ.rvdType.kType ++ coerce[TStruct](rowExpr.typ),
    entryType = coerce[TStruct](entryExpr.typ)
  )

  override def columnCount: Option[Int] = child.columnCount

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)

    val nCols = prev.nCols

    // Setup row aggregations
    val (rvAggsRow, makeInitRow, makeSeqRow, aggResultTypeRow, postAggIRRow) = ir.CompileWithAggregators[Long, Long, Long](
      "global", child.typ.globalType,
      "global", child.typ.globalType,
      "va", child.typ.rvRowType,
      rowExpr, "AGGR",
      { (nAggs, initializeIR) => initializeIR },
      { (nAggs, sequenceIR) => sequenceIR }
    )

    val (rTypRow, makeAnnotateRow) = Compile[Long, Long, Long](
      "AGGR", aggResultTypeRow,
      "global", child.typ.globalType,
      postAggIRRow)

    assert(coerce[TStruct](rTypRow) == typ.rowValueStruct, s"$rTypRow, ${ typ.rowType }")

    // Setup entry aggregations
    val (minColType, minColValues, rewriteIR) = PruneDeadFields.pruneColValues(prev, entryExpr)

    val (rvAggsEntry, makeInitEntry, makeSeqEntry, aggResultTypeEntry, postAggIREntry) = ir.CompileWithAggregators[Long, Long, Long, Long, Long](
      "global", child.typ.globalType,
      "colValues", TArray(minColType),
      "global", child.typ.globalType,
      "colValues", TArray(minColType),
      "va", child.typ.rvRowType,
      rewriteIR, "AGGR", { (nAggs, initializeIR) =>
        val colIdx = ir.genUID()

        def rewrite(x: IR): IR = {
          x match {
            case InitOp(i, args, aggSig) =>
              InitOp(
                ir.ApplyBinaryPrimOp(ir.Add(),
                  ir.ApplyBinaryPrimOp(ir.Multiply(), ir.Ref(colIdx, TInt32()), ir.I32(nAggs)),
                  i),
                args,
                aggSig)
            case _ =>
              ir.MapIR(rewrite)(x)
          }
        }

        ir.ArrayFor(
          ir.ArrayRange(ir.I32(0), ir.I32(nCols), ir.I32(1)),
          colIdx,
          ir.Let("sa", ir.ArrayRef(ir.Ref("colValues", TArray(minColType)), ir.Ref(colIdx, TInt32())),
            rewrite(initializeIR)))
      }, { (nAggs, sequenceIR) =>
        val colIdx = ir.genUID()

        def rewrite(x: IR): IR = {
          x match {
            case SeqOp(i, args, aggSig) =>
              SeqOp(
                ir.ApplyBinaryPrimOp(ir.Add(),
                  ir.ApplyBinaryPrimOp(ir.Multiply(), ir.Ref(colIdx, TInt32()), ir.I32(nAggs)),
                  i),
                args, aggSig)
            case _ =>
              ir.MapIR(rewrite)(x)
          }
        }

        ir.ArrayFor(
          ir.ArrayRange(ir.I32(0), ir.I32(nCols), ir.I32(1)),
          colIdx,
          ir.Let("sa", ir.ArrayRef(ir.Ref("colValues", TArray(minColType)), ir.Ref(colIdx, TInt32())),
            ir.Let("g", ir.ArrayRef(
              ir.GetField(ir.Ref("va", prev.typ.rvRowType), MatrixType.entriesIdentifier),
              ir.Ref(colIdx, TInt32())),
              rewrite(sequenceIR))))
      })

    val (rTypEntry, makeAnnotateEntry) = Compile[Long, Long, Long, Long](
      "AGGR", aggResultTypeEntry,
      "global", child.typ.globalType,
      "sa", minColType,
      postAggIREntry)

    val nAggsEntry = rvAggsEntry.length

    assert(coerce[TStruct](rTypEntry) == typ.entryType, s"$rTypEntry, ${ typ.entryType }")

    // Iterate through rows and aggregate
    val newRVType = typ.rvRowType
    val newRowKeyType = typ.rowKeyStruct
    val rvType = prev.typ.rvRowType
    val selectIdx = prev.typ.rvdType.kFieldIdx
    val keyOrd = prev.typ.rvdType.kRowOrd
    val localGlobalsType = prev.typ.globalType
    val localColsType = TArray(minColType)
    val colValuesBc = minColValues.broadcast
    val globalsBc = prev.globals.broadcast
    val newRVD = prev.rvd
      .repartition(prev.rvd.partitioner.strictify)
      .boundary
      .mapPartitionsWithIndex(typ.rvdType, { (i, ctx, it) =>
        val rvb = new RegionValueBuilder()
        val partRegion = ctx.freshContext.region

        rvb.set(partRegion)
        rvb.start(localGlobalsType)
        rvb.addAnnotation(localGlobalsType, globalsBc.value)
        val globals = rvb.end()

        rvb.start(localColsType)
        rvb.addAnnotation(localColsType, colValuesBc.value)
        val cols = rvb.end()

        val initializeRow = makeInitRow(i)
        val sequenceRow = makeSeqRow(i)
        val annotateRow = makeAnnotateRow(i)

        val initializeEntry = makeInitEntry(i)
        val sequenceEntry = makeSeqEntry(i)
        val annotateEntry = makeAnnotateEntry(i)

        new Iterator[RegionValue] {
          var isEnd = false
          var current: RegionValue = _
          val rvRowKey: WritableRegionValue = WritableRegionValue(newRowKeyType, ctx.freshRegion)
          val consumerRegion = ctx.region
          val newRV = RegionValue(consumerRegion)

          val colRVAggs = new Array[RegionValueAggregator](nAggsEntry * nCols)

          {
            var i = 0
            while (i < nCols) {
              var j = 0
              while (j < nAggsEntry) {
                colRVAggs(i * nAggsEntry + j) = rvAggsEntry(j).newInstance()
                j += 1
              }
              i += 1
            }
          }

          def hasNext: Boolean = {
            if (isEnd || (current == null && !it.hasNext)) {
              isEnd = true
              return false
            }
            if (current == null)
              current = it.next()
            true
          }

          def next(): RegionValue = {
            if (!hasNext)
              throw new java.util.NoSuchElementException()

            rvRowKey.setSelect(rvType, selectIdx, current)

            colRVAggs.foreach(_.clear())
            rvAggsRow.foreach(_.clear())

            initializeEntry(current.region, colRVAggs, globals, false, cols, false)
            initializeRow(current.region, rvAggsRow, globals, false)

            do {
              sequenceEntry(current.region, colRVAggs,
                globals, false,
                cols, false,
                current.offset, false)

              sequenceRow(current.region, rvAggsRow,
                globals, false,
                current.offset, false)

              current = null
            } while (hasNext && keyOrd.equiv(rvRowKey.value, current))

            rvb.set(consumerRegion)

            val rowAggResultsOffset = {
              rvb.start(aggResultTypeRow)
              rvb.startTuple()
              var j = 0
              while (j < rvAggsRow.length) {
                rvAggsRow(j).result(rvb)
                j += 1
              }
              rvb.endTuple()
              rvb.end()
            }

            val entryAggResultsOffsets = Array.tabulate(nCols) { i =>
              rvb.start(aggResultTypeEntry)
              rvb.startTuple()
              var j = 0
              while (j < nAggsEntry) {
                colRVAggs(i * nAggsEntry + j).result(rvb)
                j += 1
              }
              rvb.endTuple()
              rvb.end()
            }

            rvb.start(newRVType)
            rvb.startStruct()

            {
              rvb.addAllFields(newRowKeyType, rvRowKey.value)

              val newRowOff = annotateRow(consumerRegion,
                rowAggResultsOffset, false,
                globals, false)
              rvb.addAllFields(coerce[TStruct](rTypRow), consumerRegion, newRowOff)
            }

            rvb.startArray(nCols)

            {
              var i = 0
              while (i < nCols) {
                val newEntryOff = annotateEntry(consumerRegion,
                  entryAggResultsOffsets(i), false,
                  globals, false,
                  localColsType.loadElement(consumerRegion, cols, i), localColsType.isElementMissing(consumerRegion, cols, i))

                rvb.addRegionValue(rTypEntry, consumerRegion, newEntryOff)

                i += 1
              }
            }
            rvb.endArray()
            rvb.endStruct()
            newRV.setOffset(rvb.end())
            newRV
          }
        }
      })

    prev.copy(rvd = newRVD, typ = typ)
  }
}

case class MatrixAggregateColsByKey(child: MatrixIR, entryExpr: IR, colExpr: IR) extends MatrixIR {
  require(child.typ.colKey.nonEmpty)

  def children: IndexedSeq[BaseIR] = Array(child, entryExpr, colExpr)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixAggregateColsByKey = {
    val IndexedSeq(newChild: MatrixIR, newEntryExpr: IR, newColExpr: IR) = newChildren
    MatrixAggregateColsByKey(newChild, newEntryExpr, newColExpr)
  }

  val typ = child.typ.copyParts(
    entryType = coerce[TStruct](entryExpr.typ),
    colType = child.typ.colKeyStruct ++ coerce[TStruct](colExpr.typ))

  override def partitionCounts: Option[IndexedSeq[Long]] = child.partitionCounts

  def execute(hc: HailContext): MatrixValue = {
    val mv = child.execute(hc)

    // local things for serialization
    val oldNCols = mv.nCols
    val oldRVRowType = mv.typ.rvRowType
    val oldEntriesIndex = mv.typ.entriesIdx
    val oldColsType = TArray(mv.typ.colType)
    val oldColValues = mv.colValues
    val oldColValuesBc = mv.colValues.broadcast
    val oldGlobalsBc = mv.globals.broadcast
    val oldGlobalsType = mv.typ.globalType

    val newRVType = typ.rvRowType
    val newColType = typ.colType
    val newColKeyType = typ.colKeyStruct
    val newColValueType = typ.colValueStruct

    val keyIndices = mv.typ.colKey.map(k => mv.typ.colType.field(k).index)
    val keys = oldColValuesBc.value.map { a => Row.fromSeq(keyIndices.map(a.asInstanceOf[Row].get)) }.toSet.toArray
    val nKeys = keys.length

    val keysByColumn = oldColValues.value.map { sa => Row.fromSeq(keyIndices.map(sa.asInstanceOf[Row].get)) }
    val keyMap = keys.zipWithIndex.toMap
    val newColumnIndices = keysByColumn.map { k => keyMap(k) }.toArray
    val newColumnIndicesType = TArray(TInt32())

    // Column aggregations

    val (rvAggsColTemplate, makeInitCol, makeSeqCol, aggResultTypeCol, postAggIRCol) = ir.CompileWithAggregators[Long, Long, Long, Long](
      "global", oldGlobalsType,
      "global", oldGlobalsType,
      "colValues", oldColsType,
      "newColumnIndices", newColumnIndicesType,
      colExpr, "AGGR",
      { (nAggs, initializeIR) =>
        val colIdx = ir.genUID()

        def rewrite(x: IR): IR = {
          x match {
            case InitOp(i, args, aggSig) =>
              InitOp(ir.ApplyBinaryPrimOp(ir.Add(),
                ir.ApplyBinaryPrimOp(
                  ir.Multiply(),
                  ir.Ref(colIdx, TInt32()),
                  ir.I32(nAggs)),
                i),
                args,
                aggSig)
            case _ =>
              ir.MapIR(rewrite)(x)
          }
        }

        ir.ArrayFor(
          ir.ArrayRange(ir.I32(0), ir.I32(nKeys), ir.I32(1)),
          colIdx,
          rewrite(initializeIR)) },
      { (nAggs, sequenceIR) =>
        val colIdx = ir.genUID()

        def rewrite(x: IR): IR = {
          x match {
            case SeqOp(i, args, aggSig) =>
              SeqOp(
                ir.ApplyBinaryPrimOp(ir.Add(),
                  ir.ApplyBinaryPrimOp(
                    ir.Multiply(),
                    ir.ArrayRef(ir.Ref("newColumnIndices", newColumnIndicesType), ir.Ref(colIdx, TInt32())),
                    ir.I32(nAggs)),
                  i),
                args, aggSig)
            case _ =>
              ir.MapIR(rewrite)(x)
          }
        }

        ir.ArrayFor(
          ir.ArrayRange(ir.I32(0), ir.I32(oldNCols), ir.I32(1)),
          colIdx,
          ir.Let("sa", ir.ArrayRef(ir.Ref("colValues", oldColsType), ir.Ref(colIdx, TInt32())),
            rewrite(sequenceIR)
          ))
      }
    )

    val (rTypCol, makeAnnotateCol) = ir.Compile[Long, Long, Long](
      "AGGR", aggResultTypeCol,
      "global", oldGlobalsType,
      postAggIRCol
    )
    assert(coerce[TStruct](rTypCol) == newColValueType)

    val nAggsCol = rvAggsColTemplate.length
    val rvAggsCol = new Array[RegionValueAggregator](nAggsCol * nKeys)
    var i = 0
    while (i < nKeys) {
      var j = 0
      while (j < nAggsCol) {
        rvAggsCol(i * nAggsCol + j) = rvAggsColTemplate(j).newInstance()
        j += 1
      }
      i += 1
    }

    val rvb = new RegionValueBuilder()
    
    val newColValues = Region.scoped { region =>
      rvb.set(region)

      val globals = {
        rvb.start(oldGlobalsType)
        rvb.addAnnotation(oldGlobalsType, oldGlobalsBc.value)
        rvb.end()
      }

      val cols = {
        rvb.start(oldColsType)
        rvb.addAnnotation(oldColsType, oldColValuesBc.value)
        rvb.end()
      }

      val colIndices = {
        rvb.start(newColumnIndicesType)
        rvb.startArray(newColumnIndices.length)
        var i = 0
        while (i < newColumnIndices.length) {
          rvb.addInt(newColumnIndices(i))
          i += 1
        }
        rvb.endArray()
        rvb.end()
      }

      makeInitCol(0)(region, rvAggsCol, globals, false)
      makeSeqCol(0)(region, rvAggsCol, globals, false, cols, false, colIndices, false)

      val annotateF = makeAnnotateCol(0)

      BroadcastIndexedSeq(keys.zipWithIndex.map { case (a: Annotation, i: Int) =>
        val aggResults = {
          rvb.start(aggResultTypeCol)
          rvb.startTuple()
          var j = 0
          while (j < nAggsCol) {
            rvAggsCol(i * nAggsCol + j).result(rvb)
            j += 1
          }
          rvb.endTuple()
          rvb.end()
        }

        val colKeys = {
          rvb.start(newColKeyType)
          rvb.addAnnotation(newColKeyType, a)
          rvb.end()
        }

        val colValues = annotateF(region, aggResults, false, globals, false)

        val result = {
          rvb.start(newColType)
          rvb.startStruct()
          rvb.addAllFields(newColKeyType, region, colKeys)
          rvb.addAllFields(newColValueType, region, colValues)
          rvb.endStruct()
          rvb.end()
        }

        SafeRow(typ.colType.physicalType, region, result)
      }, TArray(typ.colType), hc.sc)
    }

    // Entry aggregations

    val transformInitOp: (Int, IR) => IR = { (nAggs, initOpIR) =>
      val colIdx = ir.genUID()

      def rewrite(x: IR): IR = {
        x match {
          case InitOp(i, args, aggSig) =>
            InitOp(ir.ApplyBinaryPrimOp(ir.Add(),
              ir.ApplyBinaryPrimOp(
                ir.Multiply(),
                ir.Ref(colIdx, TInt32()),
                ir.I32(nAggs)),
              i),
              args,
              aggSig)
          case _ =>
            ir.MapIR(rewrite)(x)
        }
      }

      ir.ArrayFor(
        ir.ArrayRange(ir.I32(0), ir.I32(nKeys), ir.I32(1)),
        colIdx,
        rewrite(initOpIR))
    }

    val transformSeqOp: (Int, IR) => IR = { (nAggs, seqOpIR) =>
      val colIdx = ir.genUID()

      def rewrite(x: IR): IR = {
        x match {
          case SeqOp(i, args, aggSig) =>
            SeqOp(
              ir.ApplyBinaryPrimOp(ir.Add(),
                ir.ApplyBinaryPrimOp(
                  ir.Multiply(),
                  ir.ArrayRef(ir.Ref("newColumnIndices", newColumnIndicesType), ir.Ref(colIdx, TInt32())),
                  ir.I32(nAggs)),
                i),
              args, aggSig)
          case _ =>
            ir.MapIR(rewrite)(x)
        }
      }

      ir.ArrayFor(
        ir.ArrayRange(ir.I32(0), ir.I32(oldNCols), ir.I32(1)),
        colIdx,
        ir.Let("sa", ir.ArrayRef(ir.Ref("colValues", oldColsType), ir.Ref(colIdx, TInt32())),
          ir.Let("g", ir.ArrayRef(
            ir.GetField(ir.Ref("va", oldRVRowType), MatrixType.entriesIdentifier),
            ir.Ref(colIdx, TInt32())),
            rewrite(seqOpIR)
          )))
    }

    val (rvAggsEntryTemplate, makeInitEntry, makeSeqEntry, aggResultTypeEntry, postAggIREntry) = ir.CompileWithAggregators[Long, Long, Long, Long, Long, Long](
      "global", oldGlobalsType,
      "va", oldRVRowType,
      "global", oldGlobalsType,
      "colValues", oldColsType,
      "va", oldRVRowType,
      "newColumnIndices", newColumnIndicesType,
      entryExpr, "AGGR",
      transformInitOp,
      transformSeqOp)

    val (rTypEntry, makeAnnotateEntry) = ir.Compile[Long, Long, Long, Long](
      "AGGR", aggResultTypeEntry,
      "global", oldGlobalsType,
      "va", oldRVRowType,
      postAggIREntry
    )
    assert(rTypEntry == typ.entryType)

    val nAggsEntry = rvAggsEntryTemplate.length
    val rvAggsEntry = new Array[RegionValueAggregator](nAggsEntry * nKeys)
    i = 0
    while (i < nKeys) {
      var j = 0
      while (j < nAggsEntry) {
        rvAggsEntry(i * nAggsEntry + j) = rvAggsEntryTemplate(j).newInstance()
        j += 1
      }
      i += 1
    }

    val mapPartitionF = { (i: Int, ctx: RVDContext, it: Iterator[RegionValue]) =>
      val rvb = new RegionValueBuilder()

      val partitionRegion = ctx.freshContext.region

      rvb.set(partitionRegion)
      rvb.start(oldGlobalsType)
      rvb.addAnnotation(oldGlobalsType, oldGlobalsBc.value)
      val partitionWideGlobalsOffset = rvb.end()

      rvb.start(oldColsType)
      rvb.addAnnotation(oldColsType, oldColValuesBc.value)
      val partitionWideColumnsOffset = rvb.end()

      rvb.start(newColumnIndicesType)
      rvb.startArray(newColumnIndices.length)
      var i = 0
      while (i < newColumnIndices.length) {
        rvb.addInt(newColumnIndices(i))
        i += 1
      }
      rvb.endArray()
      val partitionWideMapOffset = rvb.end()

      val initEntry = makeInitEntry(i)
      val sequenceEntry = makeSeqEntry(i)
      val annotateEntry = makeAnnotateEntry(i)

      it.map { rv =>
        val oldRow = rv.offset

        rvb.set(rv.region)
        rvb.start(oldGlobalsType)
        rvb.addRegionValue(oldGlobalsType, partitionRegion, partitionWideGlobalsOffset)
        val globalsOffset = rvb.end()

        rvb.set(rv.region)
        rvb.start(oldColsType)
        rvb.addRegionValue(oldColsType, partitionRegion, partitionWideColumnsOffset)
        val columnsOffset = rvb.end()

        rvb.set(rv.region)
        rvb.start(newColumnIndicesType)
        rvb.addRegionValue(newColumnIndicesType, partitionRegion, partitionWideMapOffset)
        val mapOffset = rvb.end()

        var j = 0
        while (j < rvAggsEntry.length) {
          rvAggsEntry(j).clear()
          j += 1
        }

        initEntry(rv.region, rvAggsEntry, globalsOffset, false, oldRow, false)
        sequenceEntry(rv.region, rvAggsEntry, globalsOffset, false, columnsOffset, false, oldRow, false, mapOffset, false)

        val resultOffsets = Array.tabulate(nKeys) { i =>
          var j = 0
          rvb.start(aggResultTypeEntry)
          rvb.startTuple()
          while (j < nAggsEntry) {
            rvAggsEntry(i * nAggsEntry + j).result(rvb)
            j += 1
          }
          rvb.endTuple()
          val aggResultOffset = rvb.end()
          annotateEntry(rv.region, aggResultOffset, false, globalsOffset, false, oldRow, false)
        }

        rvb.start(newRVType)
        rvb.startStruct()
        var k = 0
        while (k < newRVType.size) {
          if (k != oldEntriesIndex)
            rvb.addField(oldRVRowType, rv, k)
          k += 1
        }

        i = 0
        rvb.startArray(nKeys)
        while (i < nKeys) {
          rvb.addRegionValue(rTypEntry, rv.region, resultOffsets(i))
          i += 1
        }
        rvb.endArray()
        rvb.endStruct()
        rv.setOffset(rvb.end())
        rv
      }
    }

    val newRVD = mv.rvd.mapPartitionsWithIndex(typ.rvdType, mapPartitionF)
    mv.copy(typ = typ, colValues = newColValues, rvd = newRVD)
  }
}

case class MatrixMapEntries(child: MatrixIR, newEntries: IR) extends MatrixIR {

  def children: IndexedSeq[BaseIR] = Array(child, newEntries)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixMapEntries = {
    assert(newChildren.length == 2)
    MatrixMapEntries(newChildren(0).asInstanceOf[MatrixIR], newChildren(1).asInstanceOf[IR])
  }

  val newRow = {
    val vaType = child.typ.rvRowType
    val saType = TArray(child.typ.colType)

    val arrayLength = ArrayLen(GetField(Ref("va", vaType), MatrixType.entriesIdentifier))
    val idxEnv = new Env[IR]()
      .bind("g", ArrayRef(GetField(Ref("va", vaType), MatrixType.entriesIdentifier), Ref("i", TInt32())))
      .bind("sa", ArrayRef(Ref("sa", saType), Ref("i", TInt32())))
    val entries = ArrayMap(ArrayRange(I32(0), arrayLength, I32(1)), "i", Subst(newEntries, idxEnv))
    InsertFields(Ref("va", vaType), Seq((MatrixType.entriesIdentifier, entries)))
  }

  val typ: MatrixType =
    child.typ.copy(rvRowType = newRow.typ)

  override def partitionCounts: Option[IndexedSeq[Long]] = child.partitionCounts

  override def columnCount: Option[Int] = child.columnCount

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)

    val (minColType, minColValues, rewriteIR) = PruneDeadFields.pruneColValues(prev, newRow, isArray = true)

    val localGlobalsType = typ.globalType
    val colValuesBc = minColValues.broadcast
    val globalsBc = prev.globals.broadcast

    val (rTyp, f) = ir.Compile[Long, Long, Long, Long](
      "global", localGlobalsType,
      "va", prev.typ.rvRowType,
      "sa", minColType,
      rewriteIR)
    assert(rTyp == typ.rvRowType)

    val newRVD = prev.rvd.mapPartitionsWithIndex(typ.rvdType, { (i, ctx, it) =>
      val rvb = new RegionValueBuilder()
      val newRV = RegionValue()
      val rowF = f(i)
      val partitionRegion = ctx.freshRegion

      rvb.set(partitionRegion)
      rvb.start(localGlobalsType)
      rvb.addAnnotation(localGlobalsType, globalsBc.value)
      val globals = rvb.end()

      rvb.start(minColType)
      rvb.addAnnotation(minColType, colValuesBc.value)
      val cols = rvb.end()

      it.map { rv =>
        val region = rv.region
        val oldRow = rv.offset

        val off = rowF(region, globals, false, oldRow, false, cols, false)

        newRV.set(region, off)
        newRV
      }
    })
    prev.copy(typ = typ, rvd = newRVD)
  }
}

case class MatrixKeyRowsBy(child: MatrixIR, keys: IndexedSeq[String], isSorted: Boolean = false) extends MatrixIR {
  private val fields = child.typ.rowType.fieldNames.toSet
  assert(keys.forall(fields.contains), s"${ keys.filter(k => !fields.contains(k)).mkString(", ") }")

  val children: IndexedSeq[BaseIR] = Array(child)

  val typ: MatrixType = child.typ.copy(rowKey = keys)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixKeyRowsBy = {
    assert(newChildren.length == 1)
    MatrixKeyRowsBy(newChildren(0).asInstanceOf[MatrixIR], keys, isSorted)
  }

  override def columnCount: Option[Int] = child.columnCount

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)
    val nPreservedFields = keys.zip(prev.rvd.typ.key).takeWhile { case (l, r) => l == r }.length
    assert(!isSorted || nPreservedFields > 0 || keys.isEmpty)

    prev.copy(typ = typ, rvd = prev.rvd.enforceKey(keys, isSorted))
  }
}

case class MatrixMapRows(child: MatrixIR, newRow: IR) extends MatrixIR {

  def children: IndexedSeq[BaseIR] = Array(child, newRow)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixMapRows = {
    assert(newChildren.length == 2)
    MatrixMapRows(newChildren(0).asInstanceOf[MatrixIR], newChildren(1).asInstanceOf[IR])
  }

  val newRVRow = newRow.typ.asInstanceOf[TStruct].fieldOption(MatrixType.entriesIdentifier) match {
    case Some(f) =>
      assert(f.typ == child.typ.entryArrayType)
      newRow
    case None =>
      InsertFields(newRow, Seq(
        MatrixType.entriesIdentifier -> GetField(Ref("va", child.typ.rvRowType), MatrixType.entriesIdentifier)))
  }

  val typ: MatrixType = {
    child.typ.copy(rvRowType = newRVRow.typ.asInstanceOf[TStruct])
  }

  override def partitionCounts: Option[IndexedSeq[Long]] = child.partitionCounts

  override def columnCount: Option[Int] = child.columnCount

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)
    assert(prev.typ == child.typ)

    val (minColType, minColValues, rewriteIR) = PruneDeadFields.pruneColValues(prev, newRVRow)

    val localGlobalsType = prev.typ.globalType
    val localColsType = TArray(minColType)
    val localNCols = prev.nCols
    val colValuesBc = minColValues.broadcast
    val globalsBc = prev.globals.broadcast

    val vaType = prev.typ.rvRowType

    var scanInitNeedsGlobals = false
    var scanSeqNeedsGlobals = false
    var rowIterationNeedsGlobals = false
    var rowIterationNeedsCols = false

    val (entryAggs, initOps, seqOps, aggResultType, postAggIR) = ir.CompileWithAggregators[Long, Long, Long, Long, Long](
      "global", prev.typ.globalType,
      "va", vaType,
      "global", prev.typ.globalType,
      "colValues", localColsType,
      "va", vaType,
      rewriteIR, "AGGR",
      { (nAggs: Int, initOpIR: IR) =>
        rowIterationNeedsGlobals |= Mentions(initOpIR, "global")
        initOpIR
      },
      { (nAggs: Int, seqOpIR: IR) =>
        rowIterationNeedsGlobals |= Mentions(seqOpIR, "global")
        val seqOpNeedsCols = Mentions(seqOpIR, "sa")
        rowIterationNeedsCols |= seqOpNeedsCols

        var singleSeqOp = ir.Let("g", ir.ArrayRef(
          ir.GetField(ir.Ref("va", vaType), MatrixType.entriesIdentifier),
          ir.Ref("i", TInt32())),
          seqOpIR)

        if (seqOpNeedsCols)
          singleSeqOp = ir.Let("sa",
            ir.ArrayRef(ir.Ref("colValues", localColsType), ir.Ref("i", TInt32())),
            singleSeqOp)

        ir.ArrayFor(
          ir.ArrayRange(ir.I32(0), ir.I32(localNCols), ir.I32(1)),
          "i",
          singleSeqOp)
      })

    val (scanAggs, scanInitOps, scanSeqOps, scanResultType, postScanIR) = ir.CompileWithAggregators[Long, Long, Long](
      "global", prev.typ.globalType,
      "global", prev.typ.globalType,
      "va", vaType,
      CompileWithAggregators.liftScan(postAggIR), "SCANR",
      { (nAggs: Int, initOp: IR) =>
        scanInitNeedsGlobals |= Mentions(initOp, "global")
        initOp
      },
      { (nAggs: Int, seqOp: IR) =>
        scanSeqNeedsGlobals |= Mentions(seqOp, "global")
        rowIterationNeedsGlobals |= Mentions(seqOp, "global")
        seqOp
      })

    rowIterationNeedsGlobals |= Mentions(postScanIR, "global")

    val (rTyp, returnF) = ir.Compile[Long, Long, Long, Long, Long](
      "AGGR", aggResultType,
      "SCANR", scanResultType,
      "global", prev.typ.globalType,
      "va", vaType,
      postScanIR)
    assert(rTyp == typ.rvRowType, s"$rTyp, ${ typ.rvRowType }")

    Region.scoped { region =>
      val globals = if (scanInitNeedsGlobals) {
        val rvb = new RegionValueBuilder()
        rvb.set(region)
        rvb.start(localGlobalsType)
        rvb.addAnnotation(localGlobalsType, globalsBc.value)
        rvb.end()
      } else 0L

      scanInitOps(0)(region, scanAggs, globals, false)
    }

    val scanAggsPerPartition =
      if (scanAggs.nonEmpty) {
        prev.rvd.collectPerPartition { (i, ctx, it) =>
          val globals = if (scanSeqNeedsGlobals) {
            val rvb = new RegionValueBuilder()
            val partRegion = ctx.freshContext.region
            rvb.set(partRegion)
            rvb.start(localGlobalsType)
            rvb.addAnnotation(localGlobalsType, globalsBc.value)
            rvb.end()
          } else 0L

          val scanSeqOpF = scanSeqOps(i)

          it.foreach { rv =>
            scanSeqOpF(rv.region, scanAggs, globals, false, rv.offset, false)
            ctx.region.clear()
          }
          scanAggs
        }.scanLeft(scanAggs) { (a1, a2) =>
          (a1, a2).zipped.map { (agg1, agg2) =>
            val newAgg = agg1.copy()
            newAgg.combOp(agg2)
            newAgg
          }
        }
      } else Array.fill(prev.rvd.getNumPartitions)(Array.empty[RegionValueAggregator])

    val mapPartitionF = { (i: Int, ctx: RVDContext, it: Iterator[RegionValue]) =>
      val partitionAggs = scanAggsPerPartition(i)
      val rvb = new RegionValueBuilder()
      val newRV = RegionValue()
      val partRegion = ctx.freshContext.region

      rvb.set(partRegion)
      val globals = if (rowIterationNeedsGlobals) {
        rvb.start(localGlobalsType)
        rvb.addAnnotation(localGlobalsType, globalsBc.value)
        rvb.end()
      } else 0L

      val cols = if (rowIterationNeedsCols) {
        rvb.start(localColsType)
        rvb.addAnnotation(localColsType, colValuesBc.value)
        rvb.end()
      } else 0L

      val initOpF = initOps(i)
      val seqOpF = seqOps(i)
      val scanSeqOpF = scanSeqOps(i)
      val rowF = returnF(i)

      it.map { rv =>
        rvb.set(rv.region)

        val scanOff = if (scanAggs.nonEmpty) {
          rvb.start(scanResultType)
          rvb.startTuple()
          var j = 0
          while (j < partitionAggs.length) {
            partitionAggs(j).result(rvb)
            j += 1
          }
          rvb.endTuple()
          rvb.end()
        } else 0L

        val aggOff = if (entryAggs.nonEmpty) {
          var j = 0
          while (j < entryAggs.length) {
            entryAggs(j).clear()
            j += 1
          }

          initOpF(rv.region, entryAggs, globals, false, rv.offset, false)
          seqOpF(rv.region, entryAggs, globals, false, cols, false, rv.offset, false)

          rvb.start(aggResultType)
          rvb.startTuple()
          j = 0
          while (j < entryAggs.length) {
            entryAggs(j).result(rvb)
            j += 1
          }
          rvb.endTuple()
          rvb.end()
        } else 0L

        newRV.set(rv.region, rowF(rv.region, aggOff, false, scanOff, false, globals, false, rv.offset, false))
        scanSeqOpF(rv.region, partitionAggs, globals, false, rv.offset, false)
        newRV
      }
    }

    prev.copy(
      typ = typ,
      rvd = prev.rvd.mapPartitionsWithIndex(typ.rvdType, mapPartitionF))
  }
}

case class MatrixMapCols(child: MatrixIR, newCol: IR, newKey: Option[IndexedSeq[String]]) extends MatrixIR {
  def children: IndexedSeq[BaseIR] = Array(child, newCol)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixMapCols = {
    assert(newChildren.length == 2)
    MatrixMapCols(newChildren(0).asInstanceOf[MatrixIR], newChildren(1).asInstanceOf[IR], newKey)
  }

  val typ: MatrixType = {
    val newColType = newCol.typ.asInstanceOf[TStruct]
    val newColKey = newKey.getOrElse(child.typ.colKey)
    child.typ.copy(colKey = newColKey, colType = newColType)
  }

  override def partitionCounts: Option[IndexedSeq[Long]] = child.partitionCounts

  override def columnCount: Option[Int] = child.columnCount

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)
    assert(prev.typ == child.typ)

    val localGlobalsType = prev.typ.globalType
    val localColsType = TArray(prev.typ.colType)
    val localNCols = prev.nCols
    val colValuesBc = prev.colValues.broadcast
    val globalsBc = prev.globals.broadcast

    val colValuesType = TArray(prev.typ.colType)
    val vaType = prev.typ.rvRowType

    var initOpNeedsSA = false
    var initOpNeedsGlobals = false
    var seqOpNeedsSA = false
    var seqOpNeedsGlobals = false

    val rewriteInitOp = { (nAggs: Int, initOp: IR) =>
      initOpNeedsSA = Mentions(initOp, "sa")
      initOpNeedsGlobals = Mentions(initOp, "global")
      val colIdx = ir.genUID()

      def rewrite(x: IR): IR = {
        x match {
          case InitOp(i, args, aggSig) =>
            InitOp(
              ir.ApplyBinaryPrimOp(ir.Add(),
                ir.ApplyBinaryPrimOp(ir.Multiply(), ir.Ref(colIdx, TInt32()), ir.I32(nAggs)),
                i),
              args,
              aggSig)
          case _ =>
            ir.MapIR(rewrite)(x)
        }
      }

      val wrappedInit = if (initOpNeedsSA) {
        ir.Let(
          "sa", ir.ArrayRef(ir.Ref("colValues", colValuesType), ir.Ref(colIdx, TInt32())),
          rewrite(initOp))
      } else {
        rewrite(initOp)
      }

      ir.ArrayFor(
        ir.ArrayRange(ir.I32(0), ir.I32(localNCols), ir.I32(1)),
        colIdx,
        wrappedInit)
    }

    val rewriteSeqOp = { (nAggs: Int, seqOp: IR) =>
      seqOpNeedsSA = Mentions(seqOp, "sa")
      seqOpNeedsGlobals = Mentions(seqOp, "global")

      val colIdx = ir.genUID()

      def rewrite(x: IR): IR = {
        x match {
          case SeqOp(i, args, aggSig) =>
            SeqOp(
              ir.ApplyBinaryPrimOp(ir.Add(),
                ir.ApplyBinaryPrimOp(ir.Multiply(), ir.Ref(colIdx, TInt32()), ir.I32(nAggs)),
                i),
              args, aggSig)
          case _ =>
            ir.MapIR(rewrite)(x)
        }
      }

      var oneSampleSeqOp = ir.Let("g", ir.ArrayRef(
        ir.GetField(ir.Ref("va", vaType), MatrixType.entriesIdentifier),
        ir.Ref(colIdx, TInt32())),
        rewrite(seqOp)
      )

      if (seqOpNeedsSA)
        oneSampleSeqOp = ir.Let(
          "sa", ir.ArrayRef(ir.Ref("colValues", colValuesType), ir.Ref(colIdx, TInt32())),
          oneSampleSeqOp)

      ir.ArrayFor(
        ir.ArrayRange(ir.I32(0), ir.I32(localNCols), ir.I32(1)),
        colIdx,
        oneSampleSeqOp)
    }

    val (entryAggs, initOps, seqOps, aggResultType, postAggIR) =
      ir.CompileWithAggregators[Long, Long, Long, Long, Long](
        "global", localGlobalsType,
        "colValues", colValuesType,
        "global", localGlobalsType,
        "colValues", colValuesType,
        "va", vaType,
        newCol, "AGGR",
        rewriteInitOp,
        rewriteSeqOp)

    var scanInitOpNeedsGlobals = false

    val (scanAggs, scanInitOps, scanSeqOps, scanResultType, postScanIR) =
      ir.CompileWithAggregators[Long, Long, Long, Long](
        "global", localGlobalsType,
        "AGGR", aggResultType,
        "global", localGlobalsType,
        "sa", prev.typ.colType,
        CompileWithAggregators.liftScan(postAggIR), "SCANR",
        { (nAggs, init) =>
          scanInitOpNeedsGlobals = Mentions(init, "global")
          init
        },
        (nAggs, seq) => seq)

    val (rTyp, f) = ir.Compile[Long, Long, Long, Long, Long](
      "AGGR", aggResultType,
      "SCANR", scanResultType,
      "global", localGlobalsType,
      "sa", prev.typ.colType,
      postScanIR)

    val nAggs = entryAggs.length

    assert(rTyp == typ.colType, s"$rTyp, ${ typ.colType }")

    log.info(
      s"""MatrixMapCols: initOp ${ initOpNeedsGlobals } ${ initOpNeedsSA };
         |seqOp ${ seqOpNeedsGlobals } ${ seqOpNeedsSA }""".stripMargin)

    val depth = treeAggDepth(hc, prev.nPartitions)

    val colRVAggs = new Array[RegionValueAggregator](nAggs * localNCols)
    var i = 0
    while (i < localNCols) {
      var j = 0
      while (j < nAggs) {
        colRVAggs(i * nAggs + j) = entryAggs(j).newInstance()
        j += 1
      }
      i += 1
    }

    val aggResults = if (nAggs > 0) {
      Region.scoped { region =>
        val rvb: RegionValueBuilder = new RegionValueBuilder()
        rvb.set(region)

        val globals = if (initOpNeedsGlobals) {
          rvb.start(localGlobalsType)
          rvb.addAnnotation(localGlobalsType, globalsBc.value)
          rvb.end()
        } else 0L

        val cols = if (initOpNeedsSA) {
          rvb.start(localColsType)
          rvb.addAnnotation(localColsType, colValuesBc.value)
          rvb.end()
        } else 0L

        initOps(0)(region, colRVAggs, globals, false, cols, false)
      }

      type PC = (CompileWithAggregators.IRAggFun3[Long, Long, Long], Long, Long)
      prev.rvd.treeAggregateWithPartitionOp[PC, Array[RegionValueAggregator]](colRVAggs, { (i, ctx) =>
        val rvb = new RegionValueBuilder(ctx.freshRegion)

        val globals = if (seqOpNeedsGlobals) {
          rvb.start(localGlobalsType)
          rvb.addAnnotation(localGlobalsType, globalsBc.value)
          rvb.end()
        } else 0L

        val cols = if (seqOpNeedsSA) {
          rvb.start(localColsType)
          rvb.addAnnotation(localColsType, colValuesBc.value)
          rvb.end()
        } else 0L

        (seqOps(i), globals, cols)
      })( { case ((seqOpF, globals, cols), colRVAggs, rv) =>

        seqOpF(rv.region, colRVAggs, globals, false, cols, false, rv.offset, false)

        colRVAggs
      }, { (rvAggs1, rvAggs2) =>
        var i = 0
        while (i < rvAggs1.length) {
          rvAggs1(i).combOp(rvAggs2(i))
          i += 1
        }
        rvAggs1
      }, depth = depth)
    } else
      Array.empty[RegionValueAggregator]

    val prevColType = prev.typ.colType
    val rvb = new RegionValueBuilder()

    if (scanAggs.nonEmpty) {
      Region.scoped { region =>
        val rvb: RegionValueBuilder = new RegionValueBuilder()
        rvb.set(region)
        val globals = if (scanInitOpNeedsGlobals) {
          rvb.start(localGlobalsType)
          rvb.addAnnotation(localGlobalsType, globalsBc.value)
          rvb.end()
        } else 0L

        scanInitOps(0)(region, scanAggs, globals, false)
      }
    }

    val colsF = f(0)
    val scanSeqOpF = scanSeqOps(0)

    val newColValues = Region.scoped { region =>
      rvb.set(region)
      rvb.start(localGlobalsType)
      rvb.addAnnotation(localGlobalsType, globalsBc.value)
      val globalRVoffset = rvb.end()

      val mapF = (a: Annotation, i: Int) => {

        rvb.start(aggResultType)
        rvb.startTuple()
        var j = 0
        while (j < nAggs) {
          aggResults(i * nAggs + j).result(rvb)
          j += 1
        }
        rvb.endTuple()
        val aggResultsOffset = rvb.end()

        val colRVb = new RegionValueBuilder(region)
        colRVb.start(prevColType)
        colRVb.addAnnotation(prevColType, a)
        val colRVoffset = colRVb.end()

        rvb.start(scanResultType)
        rvb.startTuple()
        j = 0
        while (j < scanAggs.length) {
          scanAggs(j).result(rvb)
          j += 1
        }
        rvb.endTuple()
        val scanResultsOffset = rvb.end()

        val resultOffset = colsF(region, aggResultsOffset, false, scanResultsOffset, false, globalRVoffset, false, colRVoffset, false)
        scanSeqOpF(region, scanAggs, aggResultsOffset, false, globalRVoffset, false, colRVoffset, false)

        SafeRow(coerce[PStruct](rTyp.physicalType), region, resultOffset)
      }
      BroadcastIndexedSeq(colValuesBc.value.zipWithIndex.map { case (a, i) => mapF(a, i) }, TArray(typ.colType), hc.sc)
    }

    prev.copy(typ = typ, colValues = newColValues)
  }
}

case class MatrixMapGlobals(child: MatrixIR, newRow: IR) extends MatrixIR {
  val children: IndexedSeq[BaseIR] = Array(child, newRow)

  val typ: MatrixType =
    child.typ.copy(globalType = newRow.typ.asInstanceOf[TStruct])

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixMapGlobals = {
    assert(newChildren.length == 2)
    MatrixMapGlobals(newChildren(0).asInstanceOf[MatrixIR], newChildren(1).asInstanceOf[IR])
  }

  override def partitionCounts: Option[IndexedSeq[Long]] = child.partitionCounts

  override def columnCount: Option[Int] = child.columnCount

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)

    val newGlobals = Interpret[Row](
      newRow,
      Env.empty[(Any, Type)].bind(
        "global" -> (prev.globals.value, child.typ.globalType)),
      FastIndexedSeq(),
      None)

    prev.copy(typ = typ, globals = BroadcastRow(newGlobals, typ.globalType, hc.sc))
  }
}

case class MatrixFilterEntries(child: MatrixIR, pred: IR) extends MatrixIR {
  val children: IndexedSeq[BaseIR] = Array(child, pred)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixFilterEntries = {
    assert(newChildren.length == 2)
    MatrixFilterEntries(newChildren(0).asInstanceOf[MatrixIR], newChildren(1).asInstanceOf[IR])
  }

  val typ: MatrixType = child.typ

  override def partitionCounts: Option[IndexedSeq[Long]] = child.partitionCounts

  override def columnCount: Option[Int] = child.columnCount

  def execute(hc: HailContext): MatrixValue = {
    val mv = child.execute(hc)

    val (minColType, minColValues, rewriteIR) = PruneDeadFields.pruneColValues(mv, pred)

    val localGlobalType = child.typ.globalType
    val globalsBc = mv.globals.broadcast
    val colValuesBc = minColValues.broadcast

    val colValuesType = TArray(minColType)

    val x = ir.InsertFields(ir.Ref("va", child.typ.rvRowType),
      FastSeq(MatrixType.entriesIdentifier ->
        ir.ArrayMap(ir.ArrayRange(ir.I32(0), ir.I32(mv.nCols), ir.I32(1)),
          "i",
          ir.Let("g",
            ir.ArrayRef(
              ir.GetField(ir.Ref("va", child.typ.rvRowType), MatrixType.entriesIdentifier),
              ir.Ref("i", TInt32())),
            ir.If(
              ir.Let("sa", ir.ArrayRef(ir.Ref("colValues", colValuesType), ir.Ref("i", TInt32())),
                rewriteIR),
              ir.Ref("g", child.typ.entryType),
              ir.NA(child.typ.entryType))))))

    val (t, f) = ir.Compile[Long, Long, Long, Long](
      "global", child.typ.globalType,
      "colValues", colValuesType,
      "va", child.typ.rvRowType,
      x)
    assert(t == typ.rvRowType)

    val mapPartitionF = { (i: Int, ctx: RVDContext, it: Iterator[RegionValue]) =>
      val rvb = new RegionValueBuilder(ctx.freshRegion)
      rvb.start(localGlobalType)
      rvb.addAnnotation(localGlobalType, globalsBc.value)
      val globals = rvb.end()

      rvb.start(colValuesType)
      rvb.addAnnotation(colValuesType, colValuesBc.value)
      val cols = rvb.end()
      val rowF = f(i)

      val newRV = RegionValue()
      it.map { rv =>
        val off = rowF(rv.region, globals, false, cols, false, rv.offset, false)
        newRV.set(rv.region, off)
        newRV
      }
    }

    val newRVD = mv.rvd.mapPartitionsWithIndex(typ.rvdType, mapPartitionF)
    mv.copy(rvd = newRVD)
  }
}

case class MatrixAnnotateColsTable(
  child: MatrixIR,
  table: TableIR,
  root: String) extends MatrixIR {
  require(child.typ.colType.fieldOption(root).isEmpty)

  def children: IndexedSeq[BaseIR] = FastIndexedSeq(child, table)

  override def columnCount: Option[Call] = child.columnCount

  override def partitionCounts: Option[IndexedSeq[Long]] = child.partitionCounts

  private val (colType, inserter) = child.typ.colType.structInsert(table.typ.valueType, List(root))
  val typ: MatrixType = child.typ.copy(colType = colType)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixAnnotateColsTable = {
    MatrixAnnotateColsTable(
      newChildren(0).asInstanceOf[MatrixIR],
      newChildren(1).asInstanceOf[TableIR],
      root)
  }

  def execute(hc: HailContext): MatrixValue = {
    val prev =  child.execute(hc)
    val tab = table.execute(hc)

    val keyTypes = tab.typ.keyType.types
    val colKeyTypes = prev.typ.colKeyStruct.types

    val keyedRDD = tab.keyedRDD().filter { case (k, _) => !k.anyNull }

    assert(keyTypes.length == colKeyTypes.length
      && keyTypes.zip(colKeyTypes).forall { case (l, r) => l.isOfType(r) },
      s"MT col key: ${ colKeyTypes.mkString(", ") }, TB key: ${ keyTypes.mkString(", ") }")
    val r = keyedRDD.map { case (k, v) => (k: Annotation, v: Annotation) }

    val m = r.collectAsMap()
    val colKeyF = prev.typ.extractColKey

    val newAnnotations = prev.colValues.value
      .map { row =>
        val key = colKeyF(row.asInstanceOf[Row])
        val newAnnotation = inserter(row, m.getOrElse(key, null))
        newAnnotation
      }
    prev.copy(typ = typ, colValues = BroadcastIndexedSeq(newAnnotations, TArray(colType), hc.sc))
  }
}

case class MatrixAnnotateRowsTable(
  child: MatrixIR,
  table: TableIR,
  root: String,
  key: Option[IndexedSeq[IR]]) extends MatrixIR {

  def children: IndexedSeq[BaseIR] = FastIndexedSeq(child, table) ++ key.getOrElse(FastIndexedSeq.empty[IR])

  override def columnCount: Option[Call] = child.columnCount

  override def partitionCounts: Option[IndexedSeq[Long]] = child.partitionCounts

  val typ: MatrixType = child.typ.copy(rvRowType = child.typ.rvRowType ++ TStruct(root -> table.typ.valueType))

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixAnnotateRowsTable = {
    val (child: MatrixIR) +: (table: TableIR) +: newKey = newChildren
    MatrixAnnotateRowsTable(
      child, table,
      root,
      key.map { keyIRs =>
        assert(newKey.length == keyIRs.length)
        newKey.map(_.asInstanceOf[IR])
      }
    )
  }

  override def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)
    val tv = table.execute(hc)
    key match {
      // annotateRowsIntervals
      case None
        if table.typ.keyType.size == 1
        && table.typ.keyType.types(0) == TInterval(child.typ.rowKeyStruct.types(0))
      =>
        val (newRVPType, ins) =
          child.typ.rvRowType.physicalType.unsafeStructInsert(table.typ.valueType.physicalType, List(root))
        val newRVType = newRVPType.virtualType

        val rightTyp = table.typ
        val leftRVDType = child.typ.rvdType

        val zipper = { (ctx: RVDContext, it: Iterator[RegionValue], intervals: Iterator[RegionValue]) =>
          val rvb = new RegionValueBuilder()
          val rv2 = RegionValue()
          OrderedRVIterator(leftRVDType, it, ctx).leftIntervalJoinDistinct(
            OrderedRVIterator(rightTyp.rvdType, intervals, ctx)
          )
            .map { case Muple(rv, i) =>
              rvb.set(rv.region)
              rvb.start(newRVType)
              ins(
                rv.region,
                rv.offset,
                rvb,
                () => if (i == null) rvb.setMissing() else rvb.selectRegionValue(rightTyp.rowType, rightTyp.valueFieldIdx, i))
              rv2.set(rv.region, rvb.end())

              rv2
            }
        }

        val newMatrixType = child.typ.copy(rvRowType = newRVType)
        val newRVD = prev.rvd.intervalAlignAndZipPartitions(newMatrixType.rvdType, tv.rvd)(zipper)
        prev.copy(typ = typ, rvd = newRVD)

      // annotateRowsTable using non-key MT fields
      case Some(newKeys) =>
        // FIXME: here be monsters

        // used to zipWithIndex in multiple places
        val partitionCounts = child.getOrComputePartitionCounts()

        val prevRowKeys = child.typ.rowKey.toArray
        val newKeyUIDs = Array.fill(newKeys.length)(ir.genUID())
        val indexUID = ir.genUID()

        // has matrix row key and foreign join key
        val mrt = Optimize(
          MatrixRowsTable(
            MatrixMapRows(
              child,
              MakeStruct(
                prevRowKeys.zip(
                  prevRowKeys.map(rk => GetField(Ref("va", child.typ.rvRowType), rk))
                ) ++ newKeyUIDs.zip(newKeys))))
        ).execute(hc)
        val indexedRVD1 = mrt.rvd
          .zipWithIndex(indexUID, Some(partitionCounts))
        val tl1 = TableLiteral(mrt.copy(typ = mrt.typ.copy(rowType = indexedRVD1.rowType), rvd = indexedRVD1))

        // ordered by foreign key, filtered to remove null keys
        val sortedTL = Optimize(
          TableKeyBy(
            TableFilter(tl1,
              ApplyUnaryPrimOp(
                Bang(),
                newKeyUIDs
                  .map(k => IsNA(GetField(Ref("row", mrt.typ.rowType), k)))
                  .reduce[IR] { case(l, r) => ApplySpecial("||", FastIndexedSeq(l, r))})),
            newKeyUIDs)).execute(hc)

        val left = sortedTL.rvd
        val right = tv.rvd
        val joined = left.orderedLeftJoinDistinctAndInsert(right, root)

        // At this point 'joined' is sorted by the foreign key, so need to resort by row key
        // first, change the partitioner to include the index field in the key so the shuffled result is sorted by index
        val extendedKey = prev.rvd.typ.key ++ Array(indexUID)
        val rpJoined = joined.repartition(
          prev.rvd.partitioner.extendKey(joined.typ.copy(key = extendedKey).kType),
          shuffle = true)

        // the lift and dropLeft flags are used to optimize some of the struct manipulation operations
        val newRVD = prev.rvd.zipWithIndex(indexUID, Some(partitionCounts))
          .extendKeyPreservesPartitioning(prev.rvd.typ.key ++ Array(indexUID))
          .orderedLeftJoinDistinctAndInsert(rpJoined, root, lift = Some(root), dropLeft = Some(Array(indexUID)))
        MatrixValue(typ, prev.globals, prev.colValues, newRVD)

      // annotateRowsTable using key
      case None =>
        assert(child.typ.rowKeyStruct.types.zip(table.typ.keyType.types).forall { case (l, r) => l.isOfType(r) })
        val newRVD = prev.rvd.orderedLeftJoinDistinctAndInsert(
          tv.rvd, root)
        prev.copy(typ = typ, rvd = newRVD)
    }
  }
}

case class TableToMatrixTable(
  child: TableIR,
  rowKey: IndexedSeq[String],
  colKey: IndexedSeq[String],
  rowFields: IndexedSeq[String],
  colFields: IndexedSeq[String],
  nPartitions: Option[Int] = None
) extends MatrixIR {
  // no fields used twice
  private val fieldsUsed = mutable.Set.empty[String]
  (rowKey ++ colKey ++ rowFields ++ colFields).foreach { f =>
    assert(!fieldsUsed.contains(f))
    fieldsUsed += f
  }

  // need keys for rows and cols
  assert(rowKey.nonEmpty)
  assert(colKey.nonEmpty)

  def children: IndexedSeq[BaseIR] = FastIndexedSeq(child)

  def copy(newChildren: IndexedSeq[BaseIR]): TableToMatrixTable = {
    val IndexedSeq(newChild) = newChildren
    TableToMatrixTable(
      newChild.asInstanceOf[TableIR],
      rowKey,
      colKey,
      rowFields,
      colFields,
      nPartitions
    )
  }

  private val rowType = TStruct((rowKey ++ rowFields).map(f => f -> child.typ.rowType.fieldByName(f).typ): _*)
  private val colType = TStruct((colKey ++ colFields).map(f => f -> child.typ.rowType.fieldByName(f).typ): _*)
  private val entryFields = child.typ.rowType.fieldNames.filter(f => !fieldsUsed.contains(f))
  private val entryType = TStruct(entryFields.map(f => f -> child.typ.rowType.fieldByName(f).typ): _*)

  val typ: MatrixType = MatrixType.fromParts(
    child.typ.globalType,
    colKey,
    colType,
    rowKey,
    rowType,
    entryType)

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)

    val colKeyIndices = colKey.map(child.typ.rowType.fieldIdx(_)).toArray
    val colValueIndices = colFields.map(child.typ.rowType.fieldIdx(_)).toArray
    val localRowType = prev.typ.rowType
    val localRowPType = localRowType.physicalType
    val localColData = prev.rvd.mapPartitions { it =>
      it.map { rv =>
        val colKey = SafeRow.selectFields(localRowPType, rv)(colKeyIndices)
        val colValues = SafeRow.selectFields(localRowPType, rv)(colValueIndices)
        colKey -> colValues
      }
    }.reduceByKey({ case (l, _) => l }) // poor man's distinctByKey
      .collect()

    val nCols = localColData.length

    val colIndexBc = hc.sc.broadcast(localColData.zipWithIndex
      .map { case ((k, _), i) => (k, i) }
      .toMap)

    val colDataConcat = localColData.map { case (keys, values) => Row.fromSeq(keys.toSeq ++ values.toSeq): Annotation }
    val colKeysBc = hc.sc.broadcast(localColData.map(_._1))

    // allFieldIndices has all row + entry fields
    val allFieldIndices = rowKey.map(localRowType.fieldIdx(_)) ++
      rowFields.map(localRowType.fieldIdx(_)) ++
      entryFields.map(localRowType.fieldIdx(_))

    // FIXME replace with field namespaces
    val INDEX_UID = "*** COL IDX ***"

    // row and entry fields, plus an integer index
    val rowEntryStruct = rowType ++ entryType ++ TStruct(INDEX_UID -> TInt32Optional)
    val rowEntryStructPtype = rowEntryStruct.physicalType
    val rowKeyIndices = rowKey.map(rowEntryStruct.fieldIdx)
    val rowKeyF: Row => Row = r => Row.fromSeq(rowKeyIndices.map(r.get))

    val rowEntryRVD = prev.rvd.mapPartitions(RVDType(rowEntryStruct)) { it =>
      val ur = new UnsafeRow(localRowPType)
      val rvb = new RegionValueBuilder()
      val rv2 = RegionValue()

      it.map { rv =>
        rvb.set(rv.region)

        rvb.start(rowEntryStruct)
        rvb.startStruct()

        // add all non-col fields
        var i = 0
        while (i < allFieldIndices.length) {
          rvb.addField(localRowType, rv, allFieldIndices(i))
          i += 1
        }

        // look up col key, replace with int index
        ur.set(rv)
        val colKey = Row.fromSeq(colKeyIndices.map(ur.get))
        val idx = colIndexBc.value(colKey)
        rvb.addInt(idx)

        rvb.endStruct()
        rv2.set(rv.region, rvb.end())
        rv2
      }
    }

    val ordType = RVDType(rowEntryStruct, rowKey ++ FastIndexedSeq(INDEX_UID))
    val ordTypeNoIndex = RVDType(rowEntryStruct, rowKey)
    val ordered = rowEntryRVD.changeKey(ordType.key, rowKey.length)
    val orderedEntryIndices = entryFields.map(rowEntryStruct.fieldIdx)
    val orderedRowIndices = (rowKey ++ rowFields).map(rowEntryStruct.fieldIdx)

    val idxIndex = rowEntryStruct.fieldIdx(INDEX_UID)
    assert(idxIndex == rowEntryStruct.size - 1)

    val newRVType = typ.rvRowType

    val newRVD = ordered.boundary.mapPartitions(typ.rvdType, { (ctx, it) =>
      val region = ctx.region
      val rvb = ctx.rvb
      val outRV = RegionValue(region)

      OrderedRVIterator(
        ordTypeNoIndex,
        it,
        ctx
      ).staircase.map { rowIt =>
        rvb.start(newRVType)
        rvb.startStruct()
        var i = 0
        while (i < orderedRowIndices.length) {
          rvb.addField(rowEntryStruct, rowIt.value, orderedRowIndices(i))
          i += 1
        }
        rvb.startArray(nCols)
        i = 0
        var lastSeen = -1
        for (rv <- rowIt) {
          val nextInt = rv.region.loadInt(rowEntryStruct.loadField(rv.region, rv.offset, idxIndex))
          if (nextInt == lastSeen) // duplicate (RK, CK) pair
            fatal(s"'to_matrix_table': duplicate (row key, col key) pairs are not supported\n" +
              s"  Row key: ${ rowKeyF(new UnsafeRow(rowEntryStructPtype, rv)) }\n" +
              s"  Col key: ${ colKeysBc.value(nextInt) }")
          lastSeen = nextInt
          while (i < nextInt) {
            rvb.setMissing()
            i += 1
          }
          rvb.startStruct()
          var j = 0
          while (j < orderedEntryIndices.length) {
            rvb.addField(rowEntryStruct, rv, orderedEntryIndices(j))
            j += 1
          }
          rvb.endStruct()
          i += 1
        }
        while (i < nCols) {
          rvb.setMissing()
          i += 1
        }
        rvb.endArray()
        rvb.endStruct()
        outRV.setOffset(rvb.end())
        outRV
      }
    })
    MatrixValue(
      typ,
      prev.globals,
      BroadcastIndexedSeq(colDataConcat, TArray(colType), hc.sc),
      newRVD)
  }
}

case class MatrixExplodeRows(child: MatrixIR, path: IndexedSeq[String]) extends MatrixIR {
  assert(path.nonEmpty)

  def children: IndexedSeq[BaseIR] = FastIndexedSeq(child)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixExplodeRows = {
    val IndexedSeq(newChild) = newChildren
    MatrixExplodeRows(newChild.asInstanceOf[MatrixIR], path)
  }

  override def columnCount: Option[Int] = child.columnCount

  private val rvRowType = child.typ.rvRowType

  val length: IR = {
    val lenUID = genUID()
    Let(lenUID,
      ArrayLen(ToArray(
        path.foldLeft[IR](Ref("va", rvRowType))((struct, field) =>
          GetField(struct, field)))),
      If(IsNA(Ref(lenUID, TInt32())), 0, Ref(lenUID, TInt32())))
  }

  val idx = Ref(genUID(), TInt32())
  val newRVRow: InsertFields = {
    val refs = path.init.scanLeft(Ref("va", rvRowType))((struct, name) =>
      Ref(genUID(), coerce[TStruct](struct.typ).field(name).typ))

    path.zip(refs).zipWithIndex.foldRight[IR](idx) {
      case (((field, ref), i), arg) =>
        InsertFields(ref, FastIndexedSeq(field ->
          (if (i == refs.length - 1)
            ArrayRef(ToArray(GetField(ref, field)), arg)
          else
            Let(refs(i+1).name, GetField(ref, field), arg))))
    }.asInstanceOf[InsertFields]
  }

  val typ: MatrixType = child.typ.copy(rvRowType = newRVRow.typ)

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)
    val (_, l) = Compile[Long, Int]("va", rvRowType, length)
    val (t, f) = Compile[Long, Int, Long](
      "va", rvRowType,
      idx.name, TInt32(),
      newRVRow)
    assert(t == typ.rvRowType)

    MatrixValue(typ,
      prev.globals,
      prev.colValues,
      prev.rvd.boundary.mapPartitionsWithIndex(typ.rvdType, { (i, ctx, it) =>
        val region2 = ctx.region
        val rv2 = RegionValue(region2)
        val lenF = l(i)
        val rowF = f(i)
        it.flatMap { rv =>
          val len = lenF(rv.region, rv.offset, false)
          new Iterator[RegionValue] {
            private[this] var i = 0
            def hasNext(): Boolean = i < len
            def next(): RegionValue = {
              rv2.setOffset(rowF(rv2.region, rv.offset, false, i, false))
              i += 1
              rv2
            }
          }
        }
      }))
  }
}

case class MatrixUnionRows(children: IndexedSeq[MatrixIR]) extends MatrixIR {

  require(children.length > 1)

  val typ: MatrixType = children.head.typ

  require(children.tail.forall(_.typ.rvdType == typ.rvdType))
  require(children.tail.forall(_.typ.colKeyStruct == typ.colKeyStruct))

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixUnionRows =
    MatrixUnionRows(newChildren.asInstanceOf[IndexedSeq[MatrixIR]])

  override def columnCount: Option[Int] =
    children.map(_.columnCount).reduce { (c1, c2) =>
      require(c1.forall { i1 => c2.forall(i1 == _) })
      c1.orElse(c2)
    }

  def checkColKeysSame(values: IndexedSeq[IndexedSeq[Any]]): Unit = {
    val firstColKeys = values.head
    var i = 1
    values.tail.foreach { colKeys =>
      if (firstColKeys != colKeys)
        fatal(
          s"""cannot combine datasets with different column identifiers or ordering
             |  IDs in datasets[0]: @1
             |  IDs in datasets[$i]: @2""".stripMargin, firstColKeys, colKeys)
      i += 1
    }
  }

  def execute(hc: HailContext): MatrixValue = {
    val values = children.map(_.execute(hc))
    checkColKeysSame(values.map(_.colValues.value))
    val rvds = values.map(_.rvd)
    val first = rvds.head
    require(rvds.tail.forall(_.partitioner.kType == first.partitioner.kType))
    rvds.filter(_.partitioner.range.isDefined) match {
      case IndexedSeq() =>
        values.head
      case IndexedSeq(rvd) =>
        values.head.copy(rvd = rvd)
      case nonEmpty =>
        values.head.copy(rvd = RVD.union(nonEmpty))
    }
  }
}

case class MatrixExplodeCols(child: MatrixIR, path: IndexedSeq[String]) extends MatrixIR {

  def children: IndexedSeq[BaseIR] = FastIndexedSeq(child)

  def copy(newChildren: IndexedSeq[BaseIR]): MatrixExplodeCols = {
    val IndexedSeq(newChild) = newChildren
    MatrixExplodeCols(newChild.asInstanceOf[MatrixIR], path)
  }

  override def columnCount: Option[Int] = None

  override def partitionCounts: Option[IndexedSeq[Long]] = child.partitionCounts

  private val (keysType, querier) = child.typ.colType.queryTyped(path.toList)
  private val keyType = keysType match {
    case TArray(e, _) => e
    case TSet(e, _) => e
  }
  val (newColType, inserter) = child.typ.colType.structInsert(keyType, path.toList)
  val typ: MatrixType = child.typ.copy(colType = newColType)

  def execute(hc: HailContext): MatrixValue = {
    val prev = child.execute(hc)

    var size = 0
    val keys = prev.colValues.value.map { sa =>
      val ks = querier(sa).asInstanceOf[Iterable[Any]]
      if (ks == null)
        Iterable.empty[Any]
      else {
        size += ks.size
        ks
      }
    }

    val sampleMap = new Array[Int](size)
    val newColValues = new Array[Annotation](size)
    val newNCols = newColValues.length

    var i = 0
    var j = 0
    while (i < prev.nCols) {
      keys(i).foreach { e =>
        sampleMap(j) = i
        newColValues(j) = inserter(prev.colValues.value(i), e)
        j += 1
      }
      i += 1
    }

    val sampleMapBc = hc.sc.broadcast(sampleMap)
    val localEntriesIndex = prev.typ.entriesIdx
    val localEntriesType = prev.typ.entryArrayType
    val fullRowType = prev.typ.rvRowType

    prev.insertEntries(noOp, newColType = newColType,
      newColValues = prev.colValues.copy(value = newColValues, t = TArray(newColType)))(
      prev.typ.entryType,
      { case (_, rv, rvb) =>

        val entriesOffset = fullRowType.loadField(rv, localEntriesIndex)
        rvb.startArray(newNCols)
        var i = 0
        while (i < newNCols) {
          rvb.addElement(localEntriesType, rv.region, entriesOffset, sampleMapBc.value(i))
          i += 1
        }
        rvb.endArray()
      })
  }
}

/**
 * This is inteded to be an inverse to LocalizeEntries on a MatrixTable
 *
 * Some notes on semantics,
 * `rowsEntries`'s globals populate the resulting matrixtable, `cols`' are discarded
 * `entryFieldName` must refer to an array of structs field in `rowsEntries`
 * all elements in the array field that `entriesFieldName` refers to must be present. Furthermore,
 * all array elements must be the same length, though individual array items may be missing.
 */
case class UnlocalizeEntries(rowsEntries: TableIR, cols: TableIR, entryFieldName: String) extends MatrixIR {
  private val m = Map(entryFieldName -> MatrixType.entriesIdentifier)
  private val entryFieldIdx = rowsEntries.typ.rowType.fieldIdx(entryFieldName)
  private val entryFieldType = rowsEntries.typ.rowType.types(entryFieldIdx)
  private val newRowType = rowsEntries.typ.rowType.rename(m)

  entryFieldType match {
    case TArray(TStruct(_, _), _) => {}
    case _ => fatal(s"expected entry field to be an array of structs, found ${ entryFieldType }")
  }

  val typ: MatrixType = MatrixType(
    rowsEntries.typ.globalType,
    cols.typ.key,
    cols.typ.rowType,
    rowsEntries.typ.key,
    newRowType)

  def children: IndexedSeq[BaseIR] = Array(rowsEntries, cols)

  def copy(newChildren: IndexedSeq[BaseIR]): UnlocalizeEntries = {
    assert(newChildren.length == 2)
    UnlocalizeEntries(
      newChildren(0).asInstanceOf[TableIR],
      newChildren(1).asInstanceOf[TableIR],
      entryFieldName
    )
  }

  override def partitionCounts: Option[IndexedSeq[Long]] = rowsEntries.partitionCounts

  def execute(hc: HailContext): MatrixValue = {
    val rowtab = rowsEntries.execute(hc)
    val coltab = cols.execute(hc)

    val localColType = coltab.typ.rowType.physicalType
    val localColData: Array[Annotation] = coltab.rvd.mapPartitions { it =>
      it.map { rv => SafeRow(localColType, rv.region, rv.offset) : Annotation }
    }.collect

    val field = GetField(Ref("row", rowtab.typ.rowType), entryFieldName)
    val (_, lenF) = ir.Compile[Long, Int]("row", rowtab.typ.rowType,
      ir.ArrayLen(field))

    val (_, missingF) = ir.Compile[Long, Boolean]("row", rowsEntries.typ.rowType,
      ir.IsNA(field))

    var rowRVD = rowtab.rvd
    rowRVD = rowRVD.mapPartitionsWithIndex(rowRVD.typ) { (i, it) =>
      val missing = missingF(i)
      val len = lenF(i)
      it.map { rv =>
        if (missing(rv.region, rv.offset, false)) {
          fatal("missing entry array value in argument to UnlocalizeEntries")
        }
        val l = len(rv.region, rv.offset, false)
        if (l != localColData.length) {
          fatal(s"""incorrect entry array length in argument to UnlocalizeEntries:
                   |   had ${l} elements, should have had ${localColData.length} elements""".stripMargin)
        }
        rv
      }
    }
    val newRVD = rowRVD.cast(newRowType).asInstanceOf[RVD]

    MatrixValue(
      typ,
      rowtab.globals,
      BroadcastIndexedSeq(
        localColData,
        TArray(coltab.typ.rowType),
        hc.sc
      ),
      newRVD
    )
  }
}
