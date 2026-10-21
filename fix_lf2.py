import io

p = 'apps/api/src/main/scala/com/arcadence/api/LeadFinderRoutes.scala'
s = io.open(p, encoding='utf-8').read()

# 1) Fix the mangled eligible block
old1 = """                    eligible = exists == false && suppressed == false && permittedUse != "sandbox_only"
                    _ <-
                      if !eligible then FC.unit
                      else (for
                          cid <-
                            sql\"\"\"insert into public.contact (workspace_id, email, first_name, last_name, source)
                                  values ($ws, ${email.toLowerCase}, ${firstName}, ${lastName}, 'leadfinder')
                                  on conflict do nothing returning id\"\"\"
                              .query[UUID].option.flatMap {
                                case Some(cid) => FC.pure(cid)
                                case None =>
                                  sql"select id from public.contact where workspace_id = $ws and email = ${email.toLowerCase}"
                                    .query[UUID].unique
                              }
                          _ <-
                            sql\"\"\"insert into public.list_membership (list_id, contact_id)
                                  values (${body.listId}, $cid) on conflict do nothing\"\"\".update.run.void
                        yield ())
                      else FC.unit
                    yield (if eligible then n + 1 else n))
                }
            }.transact(xa)"""
new1 = """                    n2 <-
                      if eligible then
                        (for
                          cid <-
                            sql\"\"\"insert into public.contact (workspace_id, email, first_name, last_name, source)
                                  values ($ws, ${email.toLowerCase}, ${firstName}, ${lastName}, 'leadfinder')
                                  on conflict do nothing returning id\"\"\"
                              .query[UUID].option.flatMap {
                                case Some(cid) => FC.pure(cid)
                                case None =>
                                  sql"select id from public.contact where workspace_id = $ws and email = ${email.toLowerCase}"
                                    .query[UUID].unique
                              }
                          _ <-
                            sql\"\"\"insert into public.list_membership (list_id, contact_id)
                                  values (${body.listId}, $cid) on conflict do nothing\"\"\".update.run.void
                        yield n + 1)
                      else FC.pure(n)
                  yield n2
                }.transact(xa)"""
assert old1 in s, 'old1'
s = s.replace(old1, new1, 1)

# 2) Fix the _ <- orphan after update discovered_contacts
old2 = """                    and added_to_list_id is null and email is not null\"\"\".update.run

  private def envStatus(env: Envelope): SttpStatus ="""
new2 = """                    and added_to_list_id is null and email is not null\"\"\".update.run.void

  private def envStatus(env: Envelope): SttpStatus ="""
assert old2 in s, 'old2'
s = s.replace(old2, new2, 1)

io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
