## JEmuleServer 1.2.28

- **Index update: no more pauses for welcome messages.** Several eD2K index peers welcome their clients with "No
  traffic limit. No time limit - Unlimited and free server switches". Read during the first search, the word "limit"
  was taken for a warning, and the peer was left alone for 6 hours, then 24: at least 10 of the 18 pauses of the first
  day in production. The messages received before the first answer are now ignored, and only explicit warnings count
  (flooding, a ban, too many or too fast searches, a search limit reached or exceeded).
- **Cause of each pause.** The Overview's `/api/overview` gives, per peer, the time and cause of its last pause
  (warning and its text, no answer, session cut).

No database change.
