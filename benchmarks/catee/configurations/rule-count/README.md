# Rule-count configurations for CATEE and PATEE

This is the saved 36-point rule-count sweep: C1–C6 × 1, 10, 50, 100, 250, and 500 rules. User/resource data and the rule-count axis are retained.

All environmental conditions now use PATEE-supported names only: `worktime`, `workday`, `subnet`, and `server_load`. C1/C2 use two conditions, C3/C4 use three, and C5/C6 use four; the unsupported fifth condition was removed. Former synthetic conditions were mapped using their old match/nonmatch relation. See [the shared environment setup](../environment_setup.json).

This dataset has not yet been run with the revised environment semantics. The previous experiment runner explicitly rejects workday, subnet, and server_load; adapt environment provisioning and the runner before measuring these inputs. Existing completed results remain unchanged.
