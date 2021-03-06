#!/bin/bash
set -ex

source activate hail-batch

gcloud -q auth activate-service-account \
  --key-file=/secrets/gcr-push-service-account-key.json

gcloud -q auth configure-docker

# requires docker
make push-batch deploy-batch
