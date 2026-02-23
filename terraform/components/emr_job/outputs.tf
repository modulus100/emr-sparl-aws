output "job_run_output_file" {
  value = "${var.repo_root}/artifacts/emr-job-run.json"
}

output "pyspark_entrypoint_s3_uri" {
  value = "s3://${var.artifacts_bucket}/pyspark_job/main.py"
}
