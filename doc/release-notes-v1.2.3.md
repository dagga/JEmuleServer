## JEmuleServer 1.2.3

This release improves server federation, search result delivery, and default IP filter protection.

### Added

- Load server candidates from the configurable HTTPS `server.met` URL at startup. Downloaded servers are merged into
  the existing candidate list and do not erase configured or previously discovered servers.
- Enable automatic IP filter updates by default, saving the filter to `./ipfilter.dat`. The updater downloads the
  eMule Security ZIP at startup and then daily; ZIP archives are supported.

### Fixed

- Synchronize pending search results so delayed remote-search callbacks cannot race with pagination or contaminate a
  subsequent search.
- Keep searches with local results responsive while preserving the correct `more` flag when remote results are still
  arriving.

### Compatibility

The eD2K and Kad wire protocols are unchanged. Existing `server.properties` files without `ipFilterPath` now use
`./ipfilter.dat` and enable automatic updates. Set `ipFilterPath=` to disable IP filter loading and updates.
Updates that differ by 10% or more from the current filter continue to be rejected and recorded in
`ipfilter.dat.trace`.

The JAR requires Java 21 or later. Release installers bundle their Java runtime.
