# The Rhipper MCP tools

Each tool works on the Rhipper project of the IDE project that the call names with `projectPath`.
An address can be `0x401000`, `401000`, `FUN_00401000` or `F_00401000`.

## Project and pipeline

| Tool | Parameters | Returns |
|---|---|---|
| `rhipper_project` | none | The `rhipper.json` settings, the Rhipper directory, the paths of the outputs, and the state of each step. |
| `rhipper_run_step` | `step` (facts, ghidra, translate, build, run), `from` (run the later steps too), `timeoutSeconds` (1800) | The exit code and the end of the output. `exitCode: null` means that the run goes on. |
| `rhipper_stop_step` | none | Stops the pipeline run the IDE started, with its child processes: `stopped` is false when none runs. Use it to restart a step after changing a Rhipper script that the run already loaded. |
| `rhipper_step_log` | `name` (empty: list the logs), `lines` (40) | The logs in `build/pipeline`, newest first, or the last lines of one: what a running step is doing. |
| `rhipper_port_runs` | `limit` (10) | The newest runs of the port from `build/pipeline/runs.jsonl`: the exit code and the fault. |

## Functions

| Tool | Parameters | Returns |
|---|---|---|
| `rhipper_function` | `query`: an address or a name | Ghidra's C, the translated C and its file, the fidelity entry, and the clangd diagnostics in the translation. |
| `rhipper_query_functions` | see below | `total`, `offset`, `returned`, and the matching functions. |
| `rhipper_open` | `query`, `view` (decompilation, translation, disassembly) | Opens the view in the user's editor. |

The filters of `rhipper_query_functions`. Each one is optional.

| Parameter | Meaning |
|---|---|
| `text` | Text in the name or the `ref:` name, or an address inside the function. |
| `nameRegex` | A regular expression that must match a part of the name. Case does not matter. |
| `addressFrom`, `addressTo` | The range of start addresses. `addressTo` is the first address after it. |
| `minSize`, `maxSize` | The size in bytes. |
| `states` | Any of `translated`, `not_translated`, `decompiler_failed`. |
| `minScore`, `maxScore` | The fidelity score. `minScore` is inclusive, `maxScore` is exclusive. |
| `scored` | `true`: only functions that the fidelity report scored. `false`: only the others. |
| `kinds` | The fidelity kinds, for example `translated`, `stub`, `folded`, `same`. |
| `missingCall`, `extraCall` | Text in a call that only the reference makes, or only the port makes. |
| `callsDiffer` | `true`: only functions whose calls differ from the reference. |
| `sort`, `descending` | `address`, `name`, `size` or `score`. A function with no score goes last. |
| `offset`, `limit` | The page. `limit` is 50 by default and at most 5000. |
| `includeCalls` | Adds the missing and the extra calls of each function. |

## Fidelity

| Tool | Parameters | Returns |
|---|---|---|
| `rhipper_fidelity_summary` | `threshold` (0.5) | The count, the average, the count below the threshold, the perfect count and a histogram of ten ranges, for all functions and for each kind. |
| `rhipper_fidelity_changes` | `threshold` (0.5), `snapshot` ("default"), `tolerance` (0.005), `refresh`, `keepSnapshot`, `limit` | The changes since the last call with the same snapshot name: `regressed`, `improved`, `newlyBelow`, `recovered`, `added`, `removed`. The first call only takes the snapshot. |

`refresh=true` runs `fidelity32.py --quiet` (x86) or `fidelity.py --summary` (x86-64) first. That measures the built port
again and takes minutes. `keepSnapshot=true` compares with the same snapshot again, for a fixed baseline.
The snapshots last as long as the IDE runs.

### Triage

| Tool | Parameters | Returns |
|---|---|---|
| `rhipper_triage` | `batch` (0: the list), `limit` (40) | The problem functions in batches by cause, the largest first; with `batch`, its functions and their defects, and `addresses` for the next tool. |
| `rhipper_fidelity_measure` | `addresses`, `low` | Those functions (or every problem function, `low`) measured again in seconds and merged into the report: each changed score before and after. |

### Kept reports

The IDE keeps fidelity reports across pipeline runs and restarts, each with the project's and Rhipper's commits.

| Tool | Parameters | Returns |
|---|---|---|
| `rhipper_fidelity_report` | `bytes` (true), `label`, `keepOnly`, `limit` (30) | Measures the port (the fidelity script, `bytes32.py` with `bytes`, the page) and keeps the report. Returns its summary and the changes since the report before it. `keepOnly` keeps the current `build/fidelity.json` without measuring. |
| `rhipper_fidelity_reports` | `limit` (20) | The kept reports, newest first: id, commits, label, summary. |
| `rhipper_fidelity_report_compare` | `from` ("previous"), `to` ("latest"), `threshold`, `tolerance`, `limit` | The changes between two reports. A report is its id, a prefix of it, `latest` or `previous`. |
| `rhipper_fidelity_report_open` | `report` ("latest") | Opens the report's page in an editor tab. |

After a pipeline run, make a report with `rhipper_fidelity_report` rather than reading `build/fidelity.json`: the comparison
with the last report is the run's result, and the report stays after the next run.

## Diagnostics

| Tool | Parameters | Returns |
|---|---|---|
| `rhipper_clangd_diagnostics` | `minSeverity` (warning), `pathContains`, `code`, `messageContains`, `openFiles`, `waitSeconds` (60), `limit` (100) | The totals by severity, the groups by diagnostic code, the counts by file, and a list of diagnostics. `pending` lists the opened files that clangd did not analyze in time. |

clangd analyzes only the files that are open in an editor in this IDE session. `openFiles` opens more in the background.

## Reference binary and other tools

| Tool | Parameters | Returns |
|---|---|---|
| `rhipper_disassemble` | `address`, `length` (hexadecimal; empty for the whole function) | The instructions, from Rhipper's `disasm.py`. |
| `rhipper_callers` | `address` | Every direct call or jump to the function, with the caller and its source file. |
| `rhipper_xrefs` | `lo`, `hi` (empty for `lo + 1`) | Every instruction that references the range, with its function. |
| `rhipper_script` | `script` (relative to the Rhipper directory), `args`, `timeoutSeconds` (600) | The exit code and the output. A long output keeps its start and its end. |
