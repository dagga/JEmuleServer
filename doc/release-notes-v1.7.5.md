## JEmuleServer 1.7.5

### Web panel

- **A new look (#144).** The JEmuleServer logo, the mule and the blue bubble of eMule's "e", in the side bar and as
  favicon, and a palette taken from it in the spirit of the historic eMule site. Light by default; the dark theme is a
  switch at the foot of the side bar, remembered in the browser. Line icons in the menu, loading skeletons and light
  motion, turned off when the system asks for reduced motion.
- **Settings by group (#144).** Every parameter belongs to a group (Server, Clients & network, NAT traversal,
  Files & index, Searches, Protection, Kad, Other servers & relay, Database, Administration); the Settings entry of
  the menu opens a sub-menu of these groups. The filter still searches all of them.
- **Status over time (#144).** The server samples its changing figures every minute and keeps the last day in memory
  (`GET /api/status/history`): clients, LowID clients, indexed files, heap, load, banned IPs, and the refused
  connections, logins, publishers and files per minute. The Status view draws the one chosen in a list over 1, 6 or
  24 hours; the figures without a curve stay in a table.
- **Server versions and countries (#144).** The Servers view lists the version each server gives in its description
  (`ST_VERSION`, shown as eMule does: 17.15, 18.1). Servers, peers and clients get a Country column with their flag.
- **Multi-line messages (#143).** `welcomeMessage` and `loginMessage` are edited in a text area: each line typed is a
  line of the message shown in the client's server info.

### Kad

- **Words searched in the background.** The other words of a multi-word search are now searched alone on Kad from
  Kad's own rule, 3 UTF-8 bytes (was 4 characters), with the new setting `kadWordSearchMinBytes` (3 to 64).
- **Two sources to keep a Kad file.** `kadMinSourcesForIngest` defaults to 2 (was 1): a file found with a single
  source, often a fake or a file gone, is not kept.

### Upgrade notes

- No database change. The server version announced to clients stays 17.18.
- New setting `geoIpUrl`: the country database of the web panel, by default the free DB-IP "IP to Country Lite"
  (CC BY 4.0, credited in the side bar). It is downloaded once the web panel runs (about 5 MB), cached next to the
  database as `databasePath.geoip.csv.gz` and refreshed every 30 days; it takes about 7.5 MB of heap. Set it empty
  to show no country and download nothing.
- `kadMinSourcesForIngest` is only 2 on servers that do not set it: a `server.properties` that sets it to 1 keeps 1.
