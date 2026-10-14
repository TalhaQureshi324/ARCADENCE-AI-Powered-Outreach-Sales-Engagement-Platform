import io, uuid

p = 'apps/api/src/test/scala/com/arcadence/api/ImportIntegrationSpec.scala'
s = io.open(p, encoding='utf-8').read()

# temporary debug: after start of 10k test, wait and dump job + counts
old = '''      assertEquals(job.map(_.insertedRows), Some(n))'''
new = '''      assertEquals(job.map(_.insertedRows), Some(n))'''
assert old in s
# no change; we need the actual counts printed instead
old2 = '''      finalStatus <- waitForStatus(wsA, jobId, Set("completed", "failed"), attempts = 300)
      job <- importRepos.getJob(wsA, jobId)'''
new2 = '''      finalStatus <- waitForStatus(wsA, jobId, Set("completed", "failed"), attempts = 300)
      job <- importRepos.getJob(wsA, jobId)
      _ <- IO(println(s"SCRATCH10k status=$finalStatus job=${job.map(j => (j.totalRows, j.processedRows, j.insertedRows, j.invalidRows, j.errorMessage))}"))'''
assert old2 in s
s = s.replace(old2, new2)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('debug added')
