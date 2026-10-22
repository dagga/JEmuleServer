## JEmuleServer 1.2.16

Fixes the database migration of 1.2.15, which could not complete on a production database: do not use 1.2.15.

- **Migration in one pass.** The 1.2.15 migration read the whole tag table again for each batch of 50,000 rows
  (H2 chose the file hash index for its grouping), which made it last for hours on a server whose database does not
  fit in memory. The tag table is now rebuilt in a single pass in row order: the tags kept are copied into a new
  table, the source counts go to `file_sources`, then the tables are swapped. The old table is not modified before
  the swap, so that an interrupted migration starts again cleanly at the next start. The progress is logged every 5%.
- **No more database growth at startup.** H2 compacted the file in the background as soon as it was opened; while
  the startup load (or a migration) was reading, the pages it rewrote could not be freed, and the file grew by
  gigabytes until the disk was full. Background compaction is now disabled; the file is compacted at startup instead,
  before anything reads it, when less than 80% of it is used. The sizes before and after are logged.
