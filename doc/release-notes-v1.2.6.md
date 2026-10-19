## JEmuleServer 1.2.6

- Optimize `ipfilter.dat` loading performance and reduce CPU usage by replacing regular expression splitting (`split`) and stream allocations with a zero-allocation fast IP and line parser.
- Introduce binary caching (`<ipfilterPath>.bin`) for parsed IP filter ranges, allowing near-instantaneous (< 10 ms) server startup when the filter file has not changed.
- Automatic invalidation and regeneration of the binary cache upon filter file updates or downloads.
