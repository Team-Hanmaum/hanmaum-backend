"""Runner only: delegate one short-lived S3 PUT and HEAD, never AWS credentials."""

import argparse
import json
import os
from pathlib import Path
import re
import uuid


def prepare(client, bucket, account, release_id):
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,61}[a-z0-9]", bucket):
        raise ValueError("BACKUP_S3_BUCKET must be a DNS-compatible bucket name without dots")
    if not re.fullmatch(r"[0-9a-f]{40}-[0-9]+-[0-9]+", release_id):
        raise ValueError("Invalid release ID")
    scope = {"Bucket": bucket, "ExpectedBucketOwner": account}
    if client.get_bucket_location(**scope)["LocationConstraint"] != "ap-southeast-2":
        raise ValueError("Backup bucket must be in ap-southeast-2")
    public = client.get_public_access_block(**scope)["PublicAccessBlockConfiguration"]
    if not all(public.get(key) for key in ("BlockPublicAcls", "IgnorePublicAcls",
                                          "BlockPublicPolicy", "RestrictPublicBuckets")):
        raise ValueError("Enable all four S3 Block Public Access settings")
    if client.get_bucket_versioning(**scope).get("Status") != "Enabled":
        raise ValueError("Enable backup bucket versioning before deployment")
    key = f"hanmaum/postgres/{release_id}-{uuid.uuid4().hex}.dump"
    headers = {"Content-Type": "application/octet-stream",
               "x-amz-server-side-encryption": "AES256", "If-None-Match": "*"}
    put = client.generate_presigned_url("put_object", Params={
        "Bucket": bucket, "Key": key, "ContentType": headers["Content-Type"],
        "ServerSideEncryption": "AES256", "IfNoneMatch": "*",
    }, ExpiresIn=3600, HttpMethod="PUT")
    head = client.generate_presigned_url("head_object", Params={"Bucket": bucket, "Key": key},
                                         ExpiresIn=3600, HttpMethod="HEAD")
    return {"bucket": bucket, "key": key, "put": put, "head": head, "headers": headers}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        import boto3
        from botocore.config import Config
        client = boto3.client("s3", region_name="ap-southeast-2",
                              config=Config(signature_version="s3v4", s3={"addressing_style": "virtual"}))
        account = boto3.client("sts").get_caller_identity()["Account"]
        release_id = f'{os.environ["GITHUB_SHA"]}-{os.environ["GITHUB_RUN_ID"]}-{os.environ["GITHUB_RUN_ATTEMPT"]}'
        data = prepare(client, os.environ.get("BACKUP_S3_BUCKET", ""), account, release_id)
        descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(data, stream)
    except Exception:
        # SDK errors may contain signed request details. Never put them in Actions logs.
        parser.exit(1, "Backup preparation failed; check bucket, region, public-access block, versioning and role permissions.\n")
