#!/bin/sh
# One-shot MinIO bootstrap for the pilot stack (the single supported bucket-creation path).
#   1. creates the private bucket if it does not exist,
#   2. creates a least-privilege policy limited to that bucket,
#   3. creates the application's access key (STORAGE_ACCESS_KEY / STORAGE_SECRET_KEY) and attaches the policy.
# The MinIO root credentials are used only here; the API and the Worker never see them. Safe to run repeatedly.
set -eu

: "${MINIO_ROOT_USER:?}" "${MINIO_ROOT_PASSWORD:?}" "${STORAGE_BUCKET:?}" "${STORAGE_ACCESS_KEY:?}" "${STORAGE_SECRET_KEY:?}"

mc alias set pilot http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
mc mb --ignore-existing "pilot/$STORAGE_BUCKET"
# Buckets are private by default; make that explicit so a later accidental policy change is visible in the logs of this job.
mc anonymous set none "pilot/$STORAGE_BUCKET" >/dev/null

cat > /tmp/fdm-app-policy.json <<POLICY
{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect": "Allow", "Action": ["s3:ListBucket", "s3:GetBucketLocation"], "Resource": ["arn:aws:s3:::${STORAGE_BUCKET}"]},
    {"Effect": "Allow", "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"], "Resource": ["arn:aws:s3:::${STORAGE_BUCKET}/*"]}
  ]
}
POLICY

mc admin policy create pilot fdm-app /tmp/fdm-app-policy.json >/dev/null 2>&1 || mc admin policy create pilot fdm-app /tmp/fdm-app-policy.json
if mc admin user info pilot "$STORAGE_ACCESS_KEY" >/dev/null 2>&1; then
  # Rotating STORAGE_SECRET_KEY is supported: re-adding the user replaces its secret.
  mc admin user add pilot "$STORAGE_ACCESS_KEY" "$STORAGE_SECRET_KEY" >/dev/null
else
  mc admin user add pilot "$STORAGE_ACCESS_KEY" "$STORAGE_SECRET_KEY" >/dev/null
fi
mc admin policy attach pilot fdm-app --user "$STORAGE_ACCESS_KEY" >/dev/null 2>&1 || true
echo "minio-init: bucket and application access key are ready"
