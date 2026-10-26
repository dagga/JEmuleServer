## JEmuleServer 1.2.28

- **Index update: no more pauses for welcome messages.** Several eD2K index peers welcome their clients with "No
  traffic limit. No time limit - Unlimited and free server switches". Read during the first search, the word "limit"
  was taken for a warning, and the peer was left alone for 6 hours, then 24: at least 10 of the 18 pauses of the first
  day in production. The messages received before the first answer are now ignored, and only explicit warnings count
  (flooding, a ban, too many or too fast searches, a search limit reached or exceeded).
- **Cause of each pause.** The Overview's `/api/overview` gives, per peer, the time and cause of its last pause
  (warning and its text, no answer, session cut).

- **More Kad results.** A Kad node keeps up to tens of thousands of files per keyword, but a search read at most
  3 pages of 300 from each node, within the 4 seconds the client waits. A node whose third page is still full is now
  read on in the background, one page every 25 seconds (under the 3 search requests a minute a node accepts from one
  IP), while its pages come back full, until the search has 60,000 results from all its nodes together (or the node
  reaches Kad's last start position, 32,767). These files only go to the index, through the usual filters. Nothing more
  is asked while the index is full. The Searches view has a "Kad background pages" row: nodes followed, pages asked
  and answered, files accepted.

No database change.
