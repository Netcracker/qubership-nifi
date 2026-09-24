# Troubleshooting Scenarios

## Content Repository issues

### Archive cannot be cleaned

Apache NiFi periodically executes Content archive cleanup.
It's constantly trying to move active content claims into archive, if they are no longer referenced by FlowFiles.
And it also cleans up archived content claims by two conditions:
1) content repository usage reaches `nifi.content.repository.archive.max.usage.percentage` (by default, 50%)
2) archive claim age reaches `nifi.content.repository.archive.max.retention.period` (by default, 7 days).

If FlowFile's content uses more than `nifi.content.repository.archive.max.usage.percentage` of content repository space,
then cleanup for archived claims will be constantly triggered, but won't be able to cleanup anything.

### Archive cleanup is too slow

Similar to previous scenario, but this time archive cleanup takes a lot of time.
For example, it might happen when content repository is hosted on NFS volume.
Content Repository splits data in 1000+ partitions(=directories), each holding several content claims (=files).
This structure may produce lots of files splitted among lots of directories.
Apache NiFi traverses this structure to determine how much space is used and what archived claims might be removed.
On NFS volumes it may (especially on older Apache NiFi versions) take a lot of time.