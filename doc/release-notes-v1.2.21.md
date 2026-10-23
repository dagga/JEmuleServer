## JEmuleServer 1.2.21

Not published: its release build failed on a test of the online compaction, which revealed that H2's background
compaction does not shrink the file and that running an explicit compaction beside it fails. See
[1.2.22](release-notes-v1.2.22.md).
