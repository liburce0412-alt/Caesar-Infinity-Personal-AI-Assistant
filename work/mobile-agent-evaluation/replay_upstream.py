"""Replay only two inspected, pure functions from a commit-pinned official sample."""

import ast
import json
from pathlib import Path


def run_replay(fixtures):
    upstream = Path(__file__).resolve().parent / "upstream"
    provenance = json.loads((upstream / "provenance.json").read_text(encoding="utf-8-sig"))
    tree = ast.parse((upstream / "run_gui_owl_1_5_for_mobile.py").read_text(encoding="utf-8"))
    names = {"parse_action", "rescale_coordinates"}
    functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in names]
    if {node.name for node in functions} != names:
        raise AssertionError("Pinned upstream pure functions are missing")
    # Do not import the upstream module: that would load model/ADB dependencies and its main.
    namespace = {"json": json, "__builtins__": {"int": int, "ValueError": ValueError, "IndexError": IndexError}}
    exec(compile(ast.Module(body=functions, type_ignores=[]), provenance["source_path"], "exec"), namespace)
    parse = namespace["parse_action"]
    rescale = namespace["rescale_coordinates"]
    dispatch_actions = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Compare) and isinstance(node.left, ast.Name) and node.left.id == "action_type":
            for value in node.comparators:
                if isinstance(value, ast.Constant) and isinstance(value.value, str):
                    dispatch_actions.add(value.value)
                elif isinstance(value, (ast.Tuple, ast.List)):
                    dispatch_actions.update(item.value for item in value.elts if isinstance(item, ast.Constant) and isinstance(item.value, str))
    valid_outputs = set()
    for case in fixtures:
        for output in case["outputs"]:
            if output.count("<tool_call>") != 1:
                continue
            candidate = json.loads(output.split("<tool_call>", 1)[1].split("</tool_call>", 1)[0])
            if candidate["name"] == "mobile_use" and candidate["arguments"].get("action") in dispatch_actions:
                valid_outputs.add(output)
    for output in valid_outputs:
        expected = json.loads(output.split("<tool_call>", 1)[1].split("</tool_call>", 1)[0])
        if parse(output) != expected:
            raise AssertionError("Official parse_action disagrees with the fixed protocol fixture")
    portrait = rescale({"coordinate": [500, 250]}, 1080, 2400)
    if portrait != {"coordinate": [540, 600]}:
        raise AssertionError("Official portrait coordinate replay failed")
    landscape = rescale({"coordinate": [250, 500], "coordinate2": [750, 500]}, 1920, 1080)
    if landscape != {"coordinate": [480, 540], "coordinate2": [1440, 540]}:
        raise AssertionError("Official swipe coordinate replay failed")
    try:
        parse("no action block")
    except ValueError:
        pass
    else:
        raise AssertionError("Official malformed input replay changed")
    return {
        "commit": provenance["commit"],
        "source_url": provenance["source_url"],
        "executed_functions_only": sorted(names),
        "distinct_protocol_outputs_replayed": len(valid_outputs),
        "action_names_checked_against_official_dispatch": True,
        "coordinate_cases": 2,
        "malformed_input_cases": 1,
        "upstream_main_executed": False,
        "model_or_device_success_claimed": False,
    }
