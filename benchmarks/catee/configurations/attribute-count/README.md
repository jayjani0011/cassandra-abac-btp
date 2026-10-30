# Attribute Count configurations for CATEE and PATEE

36 points; C1–C6 × 1, 2, 4, 8, 12, and 16 attributes per entity.

Environmental conditions use only the four names supported by PATEE: `worktime`, `workday`, `subnet`, and `server_load`. The former synthetic conditions were mapped to supported names with matching/nonmatching values under the common test context; a fifth condition was removed where present. See [../environment_setup.json](../environment_setup.json) for the observation, subnet, and load assumptions.

The planned independent variable is user and resource attributes per entity. `points.csv` names each input set. Configuration loading remains outside timed measurement. These inputs are not ready to run with the previous CATEE experiment runner: the runner and environment provisioning must later be adapted for workday, subnet, and server_load. Completed experiment results are preserved and are not regenerated from these revised inputs.
