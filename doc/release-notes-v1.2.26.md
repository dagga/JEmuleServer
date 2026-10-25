## JEmuleServer 1.2.26

- **Child abuse searches never leave the server.** A search whose terms match the child abuse filter, from a client
  (`OP_SEARCHREQUEST`) or a UDP global search, now gets no result and is sent neither to Kad nor to the other eD2K
  servers. Until 1.2.25 a client's search was forwarded to Kad and to the servers as is (only its results were
  screened). These searches are counted on the Overview.
- **Last check before the client.** Every batch of search results is checked against the filter before it is sent,
  whatever brought the files; the local search also skips a file indexed before the keywords were reloaded.

The names the filter rejects are still recorded in `banned_names` (1.2.25) and never sent to a client. No database
change.
