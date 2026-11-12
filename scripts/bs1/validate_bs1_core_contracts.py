"""Validate opt-in BS1 examples against v3 context and unchanged frozen E1 contracts.

Requires the existing jsonschema validator environment. No resource/network queries.
Input is the SYNTHETIC array emitted by Bs1CoreTest with varvis.bs1.contractOutput.
"""
import argparse
import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("examples", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    sources = list((root / "docs/contracts/acmg-evidence-v2/schemas").glob("*.schema.json"))
    sources.append(root / "varvis-server/src/main/resources/acmg/evidence-context-v3.schema.json")
    schemas = [json.loads(path.read_text(encoding="utf-8")) for path in sources]
    registry = Registry().with_resources((s["$id"], Resource.from_contents(s)) for s in schemas)
    validators = {}
    for key, name in [("context", "evidence-context-v3"), ("evidence", "atomic-evidence-v2"), ("evaluation", "criterion-evaluation-v2")]:
        schema = next(s for s in schemas if s["$id"] == "urn:varvis:schema:" + name)
        validators[key] = Draft202012Validator(schema, registry=registry, format_checker=FormatChecker())
    values = json.loads(args.examples.read_text(encoding="utf-8"))
    if not isinstance(values, list) or not values:
        raise ValueError("A nonempty synthetic example array is required")
    errors = []
    for index, example in enumerate(values):
        for key, validator in validators.items():
            for error in validator.iter_errors(example.get(key)):
                errors.append({"example": index, "object": key, "path": error.json_path, "reason": error.message})
    print(json.dumps({"schemaChecks": len(values) * 3, "errorCount": len(errors), "errors": errors}, ensure_ascii=True))
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
