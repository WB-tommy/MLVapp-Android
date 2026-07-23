# Archived split patch stack

These six patches are the pre-consolidation Android patch stack. They are kept
only for provenance and for reviewing how individual Android features evolved.

They are not supported against desktop commit
`877dea2cb9413bd0542abb622af517cf12db63d3`: patches 01 and 06 fail clean
application, and the old applicator could continue after a failure, producing
a partially patched shared-native tree.

Use `../../current_upstream_android_delta.patch.gz` through `../../apply_all.sh`
for the current baseline.
