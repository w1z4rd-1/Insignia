Insignia Diagnose Bundle
=======================

Mode: FULL
Spark detected: true

Artifacts:
- presentmon.csv
- recording.jfr
- bad_frames.json
- windows_hardware_counters.csv
- process_contention.csv
- system_info.json
- logs/*.log

How to inspect recording.jfr:
- Open with JDK Mission Control (JMC), then inspect Method Profiling / Memory / Lock Instances.

Nsight Systems docs:
- https://docs.nvidia.com/nsight-systems/AnalysisGuide/index.html

Frame boundary notes:
- HUD render callback frame boundaries were recorded as a stable frame proxy.

CapFrameX note:
- CapFrameX is a popular PresentMon analysis tool: https://github.com/CXWorld/CapFrameX
- Analysts/AI may use CapFrameX-style interpretation and Python to analyze this bundle.

Optimized ChatGPT Analysis Prompt:
- Analyze this diagnostics bundle for Minecraft stutter root causes. Use presentmon.csv for frame-time outliers,
  recording.jfr for CPU/allocation/lock/GC correlation, and optional ETL/nsys outputs for system behavior.
  Produce: (1) top likely root causes, (2) confidence-ranked evidence with timestamps, (3) specific mod/config/runtime fixes,
  (4) prioritized next measurements to disambiguate uncertainty. If internet access is available, search for current mods or
  settings known to mitigate the identified issue patterns and cite them.

Notes:
- typeperf capture started.
- nvidia-smi capture started.
- Dispatched spark profiler command.
- nsys UAC fallback failed with exitCode=1 timedOut=false
- nsys timed profile console fallback exitCode=0 timedOut=false
- WPR UAC fallback exitCode=-984076287 timedOut=false
- WPR UAC fallback reported already-running profile. recording=false
- WPR cancel result exitCode=0 timedOut=false
- WPR UAC retry exitCode=0 timedOut=false
- WPR status after UAC retry: recording=false
- WPR start failed with exitCode=-984068079 timedOut=false
- typeperf capture stopped.
- nvidia-smi capture stopped.
- spark output directory not found.
- Fetched spark profile from URL.
- Copied spark outputs: 1
- No trace.etl produced; skipping ETL exports.
- External Nsight report pickup copied files: 0
- Included file: latest.log
- Included file: options.txt
- Included file: sodium-options.json
- Included file: sodium-extra-options.json
- Included file: lithium.properties

Privacy reminder:
- Before sharing, review files for anything sensitive.
