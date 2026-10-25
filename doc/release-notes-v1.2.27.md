## JEmuleServer 1.2.27

- **Index update from eD2K index peers (opt-in).** With `serverIndexUpdateEnabled=true`, the server logs in to each
  eD2K index peer (a verified eD2K server) as an eMule client and searches there the words its index holds most often and the keywords
  of its clients' searches (without their filters), then indexes the files found. A TCP search returns up to 300
  files where a UDP global search returns about 10. A full page is searched again per file type, and a full type per
  size (1–900 MB, 900–2000 MB, above 2000 MB).
- Each server has its own session and schedule: one search at a time, batches of 6 searches at most, at most
  `serverIndexUpdateMaxPerHour` (40) an hour and `serverIndexUpdateMaxPerDay` (300) a day. A server that cuts the
  session, stops answering or warns is left alone for hours.
- The files found go through the usual filters; those with at least `serverIndexUpdateMinSources` (3) sources are
  indexed, under the new ingestion channel `server index update`. The Overview counts the searches, the results and
  the new files. Kad is not searched this way.

No database change.
