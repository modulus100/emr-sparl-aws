output "raw_bucket_name" {
  value = aws_s3_bucket.this["raw"].bucket
}

output "warehouse_bucket_name" {
  value = aws_s3_bucket.this["warehouse"].bucket
}

output "artifacts_bucket_name" {
  value = aws_s3_bucket.this["artifacts"].bucket
}
