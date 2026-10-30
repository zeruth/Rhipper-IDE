---
name: rhipper
description: Work on a Rhipper project (a rhipper.json at the root) through the Rhipper IDE's MCP tools. Use it to investigate a crash of the port, raise the fidelity of the translation, find functions, read Ghidra's decompilation or the translated C, run pipeline steps, or read the clangd diagnostics of the generated sources.
---

# Rhipper

Rhipper recompiles a Windows PE binary to C. It reads facts off the binary in Python, applies them in headless Ghidra, exports
the decompilation of every function to a corpus, and translates the corpus to C that builds and runs. The Rhipper IDE runs
these steps and exposes the project through MCP tools whose names start with `rhipper_`.

## The rule

Fix a defect where it arises: in a fact, or in the translator's reading of a construct. Never patch one function by hand.
A translation must compile in one round.

So do not edit the files under the `gen` directory (`src/gen` by default). The translator writes them again on each run.
Find the cause and fix it in one of these places:

| The cause | Where the fix goes | The step to run again |
|---|---|---|
| A wrong or missing function name | `data/names.json` of the project | ghidra, then translate |
| A placeholder type that needs a layout | `data/structs.json` of the project | ghidra, then translate |
| A wrong prototype, vtable, switch or other fact | the fact extractor in the Rhipper directory | facts, then ghidra, then translate |
| The translator reads a construct wrongly | `translate32.py` or `translate64.py` in the Rhipper directory | translate |
| The runtime of the port | the runtime sources of the project | build |

`rhipper_project` gives the Rhipper directory and the paths of the project.

## Start here

1. Call `rhipper_project`. It returns the settings of `rhipper.json`, the Rhipper directory, the outputs that exist, and the
   state of each pipeline step. The architecture (`x86` or `x86_64`) decides which translator and runtime apply.
2. Call `rhipper_fidelity_summary` for the state of the port, and `rhipper_port_runs` for its last runs.

## Names and addresses

- `FUN_00401000` is Ghidra's name of the reference function at `0x401000`. `F_00401000` is the translated C function for it.
- Every tool takes an address as `0x401000`, `401000`, `FUN_00401000`, `F_00401000`, or an address inside the function.
- A translated function has a `/* ref: <name> */` comment above it. The name comes from Ghidra, RTTI or `data/names.json`.
- A fault such as `F_00e14a10+0x126 c0000005 reading 0` names the translated function and the offset in it. `?+0x0` means a
  jump to address 0: a call through a null function pointer. No function holds that address.

## Tasks

### Find functions

Use `rhipper_query_functions`. Every filter is optional, and a function must match all filters that are set. The result
gives the `total`, so page with `offset` and `limit`. Some examples:

- The worst translated functions: `states=["translated"]`, `scored=true`, `sort="score"`.
- Functions below 0.5 that miss a call: `maxScore=0.5`, `callsDiffer=true`, `includeCalls=true`.
- One class: `nameRegex="^Apricot::Engine::Data::"`.
- One range of the binary: `addressFrom="0x9e0000"`, `addressTo="0xa00000"`.
- Functions that the decompiler failed on: `states=["decompiler_failed"]`.

### Read a function

`rhipper_function` returns Ghidra's decompilation, the translated C, the fidelity entry, and the clangd diagnostics in the
lines of the translation. `clangdAnalyzed=false` means that clangd has not analyzed the file yet. In that case, an empty
diagnostics list says nothing. Then use `rhipper_disassemble` for the instructions of the reference, `rhipper_callers` for
its callers, and `rhipper_xrefs` for the instructions that read or write a global.

`rhipper_open` opens a view of the function in the user's editor. Use it when the user wants to look at the code too.

### Raise the fidelity

1. `rhipper_fidelity_summary`: the scores by kind. A `stub` scores 0 because it has no port yet. Leave the stubs out of a
   translation question with `kinds=["translated"]`.
2. `rhipper_query_functions` with `scored=true`, `sort="score"` and `includeCalls=true`: the worst functions and the calls
   that differ. A missing call often comes from a wrong prototype or a wrong `noreturn` fact. A call that only the port makes
   often comes from a helper that the compiler of the reference inlined.
3. Read the worst functions with `rhipper_function`. Look for one cause that many functions share. Fix that cause by the rule.
4. Call `rhipper_fidelity_changes` before the fix. It takes a snapshot.
5. Run the steps that the fix needs, then call `rhipper_fidelity_changes` with `refresh=true`. It measures the port again,
   which takes minutes. It lists the functions that regressed, improved, fell below the threshold, or rose above it.
   Report the regressions before anything else.

### Investigate a crash of the port

1. `rhipper_port_runs`: the exit code and the fault of the latest runs.
2. When the fault names a function, read it with `rhipper_function`, and compare the translation with Ghidra's C at the offset.
3. When the fault is `?+0x0` or the cause is not local, find the first call where the port and the reference part.
   Run `shadow_diff.py` through `rhipper_script` on a shadow trace of the reference and one of the port. `shadow32.py` (x86)
   or `shadow64.py` (x86-64) records the trace of the reference.

### Read the clangd diagnostics

`rhipper_clangd_diagnostics` groups the errors and warnings of the analyzed files by diagnostic code. A code that many
generated files share is likely a pattern of the translator, and fixing it fixes all of them. clangd analyzes only open
files. Pass `openFiles` with the generated files to look at, and allow about a minute for each large one.

### Run the pipeline

The steps, in order: `facts`, `ghidra`, `translate`, `build`, `run`. `rhipper_run_step` runs one step, or with
`from=true` that step and every step after it. The run shows in the IDE's Run tool window, where the user can watch it.

- `facts` and `ghidra` take many minutes on a large binary. Run them only when a fact changed.
- Do not run `run` without a reason: it starts the port and its side processes.
- When the wait times out, the result has `exitCode: null`, and the run goes on. Check it later with `rhipper_project`, and
  follow it with `rhipper_step_log`: with no name it lists the step logs newest first, the newest being the one a running step
  writes.
- A 32-bit pipeline runs `jobs` workers (rhipper.json, default 4). Set fewer where another translation shares the machine.

### Use another Rhipper tool

`rhipper_script` runs any Python tool of the Rhipper directory in the project. Examples:
`corpus.py --addr 401000`, `fidelity32.py --show 0x401000`, `callers.py 0x401000`, `shadow_diff.py <ref> <port>`.
Read the docstring at the top of a tool for its arguments.

## Tool reference

See [references/mcp-tools.md](references/mcp-tools.md) for the parameters of each tool.
