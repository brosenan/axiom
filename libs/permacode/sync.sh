#!/bin/bash
aws s3 sync ~/.permacode/ s3://$PERMACODE_S3_BUCKET
aws s3 sync s3://$PERMACODE_S3_BUCKET ~/.permacode/
