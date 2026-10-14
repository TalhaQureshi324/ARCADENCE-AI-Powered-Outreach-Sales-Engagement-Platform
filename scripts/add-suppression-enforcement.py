import io

p = 'apps/api/src/main/scala/com/arcadence/api/ImportService.scala'
s = io.open(p, encoding='utf-8').read()

lines = s.split('\n')
out = []
in_target = False
inserted_suppress_open = False
depth_added = False

i = 0
while i < len(lines):
    line = lines[i]
    if 'existingLookup.flatMap { existing =>' in line:
        out.append(line)
        out.append('        // suppression enforcement (Phase 3.3): suppressed emails never import')
        out.append('        leadsRepos.suppressedEmails(workspaceId, emails).flatMap { suppressed =>')
        inserted_suppress_open = True
        i += 1
        continue
    out.append(line)
    i += 1

s = '\n'.join(out)

# close: find the batch-result `.map { (ins, upd, skip, inv, dup, _) =>` accumulate block and append a closing brace
old = '''          (acc._1 + delta._1, acc._2 + delta._2, acc._3 + delta._3, acc._4 + delta._4, acc._5 + delta._5, acc._6 + delta._6)
        }
      }
    }.flatMap { totals =>'''
new = '''          (acc._1 + delta._1, acc._2 + delta._2, acc._3 + delta._3, acc._4 + delta._4, acc._5 + delta._5, acc._6 + delta._6)
        }
        }
      }
    }.flatMap { totals =>'''
assert old in s, 'close'
s = s.replace(old, new)

# classification: add suppressed branch
old = '''        candidates.foreach { (rowNumber, email, row) =>
          existing.get(email) match'''
new = '''        candidates.foreach { (rowNumber, email, row) =>
          if suppressed.contains(email) then
            skippedExisting += 1
            dupErrs += ((rowNumber, s"suppressed: $email"))
          else existing.get(email) match'''
assert old in s, 'classify'
s = s.replace(old, new)

io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('suppression enforcement added')
