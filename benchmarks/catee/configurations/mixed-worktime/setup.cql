-- Run as a superuser against an enabled CATEE node before measurements.
SET ENV_ATTRIBUTE worktime = 0,0,23,59;
SET ENV_ATTRIBUTE workday = monday,tuesday,wednesday,thursday,friday,saturday,sunday;
SET ENV_ATTRIBUTE subnet = benchmark_allow='0.0.0.0/0';
SET ENV_ATTRIBUTE subnet = benchmark_deny='192.0.2.0/24';
