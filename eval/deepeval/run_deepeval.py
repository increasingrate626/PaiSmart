from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from xml.etree import ElementTree


REQUIRED_KEYS = ("DEEPSEEK_API_KEY", "DASHSCOPE_API_KEY")


@dataclass(frozen=True)
class Thresholds:
    faithfulness: float = 0.70
    citation_accuracy: float = 0.80
    fixed_version_accuracy: float = 0.80


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    artifacts_dir = Path(args.artifacts_dir)
    artifacts_dir.mkdir(parents=True, exist_ok=True)

    missing_keys = [key for key in REQUIRED_KEYS if not os.getenv(key)]
    if missing_keys and not args.input_json:
        summary = {
            "skipped": True,
            "reason": "Missing required provider keys; CI is configured to skip DeepEval in this state.",
            "missingKeys": missing_keys,
            "qualityGatePassed": True,
        }
        write_artifacts(artifacts_dir, summary, [])
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0

    thresholds = Thresholds(
        faithfulness=float(os.getenv("RAG_EVAL_FAITHFULNESS_THRESHOLD", args.faithfulness_threshold)),
        citation_accuracy=float(os.getenv("RAG_EVAL_CITATION_ACCURACY_THRESHOLD", args.citation_threshold)),
        fixed_version_accuracy=float(os.getenv("RAG_EVAL_FIXED_VERSION_ACCURACY_THRESHOLD", args.fixed_version_threshold)),
    )

    try:
        export = load_export(args)
        case_results = evaluate_cases(export.get("cases", []), thresholds, args.skip_faithfulness)
        summary = evaluate_quality_gate(case_results, thresholds)
        summary.update({
            "skipped": False,
            "run": export.get("run", {}),
            "thresholds": {
                "faithfulness": thresholds.faithfulness,
                "citationAccuracy": thresholds.citation_accuracy,
                "fixedVersionAccuracy": thresholds.fixed_version_accuracy,
            },
        })
        if not args.input_json:
            post_metrics(args, export.get("run", {}).get("id"), summary, case_results)
        write_artifacts(artifacts_dir, summary, case_results)
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0 if summary["qualityGatePassed"] else 1
    except Exception as exc:
        summary = {
            "skipped": False,
            "qualityGatePassed": False,
            "failures": ["HARNESS_EXCEPTION"],
            "error": str(exc),
        }
        write_artifacts(artifacts_dir, summary, [])
        print(json.dumps(summary, ensure_ascii=False, indent=2), file=sys.stderr)
        return 1


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run PaiSmart Agentic RAG DeepEval regression.")
    parser.add_argument("--base-url", default=os.getenv("PAISMART_BASE_URL", "http://localhost:8081"))
    parser.add_argument("--username", default=os.getenv("PAISMART_ADMIN_USERNAME", "admin"))
    parser.add_argument("--password", default=os.getenv("PAISMART_ADMIN_PASSWORD", "changeme"))
    parser.add_argument("--user-id", default=os.getenv("PAISMART_RAG_EVAL_USER_ID", "admin"))
    parser.add_argument("--note", default=os.getenv("PAISMART_RAG_EVAL_NOTE", "deepeval-offline"))
    parser.add_argument("--input-json", default=None, help="Use a pre-exported run result JSON instead of calling backend.")
    parser.add_argument("--artifacts-dir", default="target/rag-eval")
    parser.add_argument("--skip-faithfulness", action="store_true", help="Skip LLM judge and set faithfulness to 1.0.")
    parser.add_argument("--faithfulness-threshold", type=float, default=0.70)
    parser.add_argument("--citation-threshold", type=float, default=0.80)
    parser.add_argument("--fixed-version-threshold", type=float, default=0.80)
    return parser.parse_args(argv)


def load_export(args: argparse.Namespace) -> dict[str, Any]:
    if args.input_json:
        return json.loads(Path(args.input_json).read_text(encoding="utf-8"))
    return fetch_backend_export(args)


def fetch_backend_export(args: argparse.Namespace) -> dict[str, Any]:
    import requests

    base_url = args.base_url.rstrip("/")
    session = requests.Session()
    login_response = session.post(
        f"{base_url}/api/v1/users/login",
        json={"username": args.username, "password": args.password},
        timeout=30,
    )
    login_response.raise_for_status()
    token = login_response.json()["data"]["token"]
    session.headers.update({"Authorization": f"Bearer {token}"})

    session.post(f"{base_url}/api/v1/admin/rag-eval/corpus/import-default", timeout=120).raise_for_status()
    session.post(f"{base_url}/api/v1/admin/rag-eval/cases/import-default", timeout=120).raise_for_status()
    run_response = session.post(
        f"{base_url}/api/v1/admin/rag-eval/runs",
        params={"userId": args.user_id, "note": args.note},
        timeout=600,
    )
    run_response.raise_for_status()
    run_id = run_response.json()["data"]["id"]
    export_response = session.get(f"{base_url}/api/v1/admin/rag-eval/runs/{run_id}/results", timeout=120)
    export_response.raise_for_status()
    return export_response.json()["data"]


def post_metrics(args: argparse.Namespace, run_id: Any, summary: dict[str, Any], cases: list[dict[str, Any]]) -> None:
    if run_id is None:
        raise ValueError("Cannot post DeepEval metrics without a run id")

    import requests

    base_url = args.base_url.rstrip("/")
    session = requests.Session()
    login_response = session.post(
        f"{base_url}/api/v1/users/login",
        json={"username": args.username, "password": args.password},
        timeout=30,
    )
    login_response.raise_for_status()
    token = login_response.json()["data"]["token"]
    response = session.post(
        f"{base_url}/api/v1/admin/rag-eval/runs/{run_id}/metrics",
        headers={"Authorization": f"Bearer {token}"},
        json={"summary": summary, "cases": cases},
        timeout=120,
    )
    response.raise_for_status()


def evaluate_cases(cases: list[dict[str, Any]], thresholds: Thresholds, skip_faithfulness: bool) -> list[dict[str, Any]]:
    faithfulness_scores = score_faithfulness(cases, thresholds.faithfulness, skip_faithfulness)
    results = []
    for case in cases:
        citation_score, citation_hits = citation_accuracy(case)
        fixed_score, fixed_hits = fixed_version_accuracy(case)
        recall_score, recall_hits = recall_at_k(case, 8)
        case_id = case.get("caseId")
        result = {
            "caseId": case_id,
            "question": case.get("question", ""),
            "answer": case.get("answer", ""),
            "expectedEvidence": list_values(case.get("expectedEvidence")),
            "expectedFixedVersions": list_values(case.get("expectedFixedVersions")),
            "faithfulnessScore": faithfulness_scores.get(str(case_id), 0.0),
            "citationAccuracy": citation_score,
            "fixedVersionAccuracy": fixed_score,
            "recallAt8": recall_score,
            "matchedEvidence": citation_hits,
            "matchedFixedVersions": fixed_hits,
            "matchedRecallAt8Evidence": recall_hits,
        }
        results.append(result)
    return results


def score_faithfulness(cases: list[dict[str, Any]], threshold: float, skip_faithfulness: bool) -> dict[str, float]:
    if skip_faithfulness:
        return {str(case.get("caseId")): 1.0 for case in cases}

    from deepeval.metrics import FaithfulnessMetric
    from deepeval.test_case import LLMTestCase

    metric = FaithfulnessMetric(
        threshold=threshold,
        model=deepseek_judge_model_from_env(),
        include_reason=True,
        async_mode=False,
    )
    scores: dict[str, float] = {}
    for case in cases:
        payload = build_test_case_payload(case)
        test_case = LLMTestCase(
            input=payload["input"],
            actual_output=payload["actual_output"],
            retrieval_context=payload["retrieval_context"],
        )
        metric.measure(test_case)
        scores[str(case.get("caseId"))] = float(metric.score or 0.0)
    return scores


def build_test_case_payload(case: dict[str, Any]) -> dict[str, Any]:
    retrieval_context = evidence_texts(case.get("selectedEvidence"))
    if not retrieval_context:
        final_context = str(case.get("finalContext") or "").strip()
        retrieval_context = [final_context] if final_context else []
    return {
        "input": str(case.get("question") or ""),
        "actual_output": str(case.get("answer") or ""),
        "retrieval_context": retrieval_context,
    }


def citation_accuracy(case: dict[str, Any]) -> tuple[float, list[str]]:
    expected = list_values(case.get("expectedEvidence"))
    if not expected:
        return 1.0, []
    actual_ids = evidence_ids(case.get("selectedEvidence"))
    actual_ids.update(answer_reference_ids(case))
    matched = [item for item in expected if item in actual_ids]
    return len(matched) / len(expected), matched


def recall_at_k(case: dict[str, Any], k: int) -> tuple[float, list[str]]:
    expected = list_values(case.get("expectedEvidence"))
    if not expected:
        return 1.0, []
    actual_ids = evidence_ids(selected_evidence_items(case.get("selectedEvidence"))[:max(k, 0)])
    matched = [item for item in expected if item in actual_ids]
    return len(matched) / len(expected), matched


def fixed_version_accuracy(case: dict[str, Any]) -> tuple[float, list[str]]:
    expected = list_values(case.get("expectedFixedVersions"))
    if not expected:
        return 1.0, []
    answer = str(case.get("answer") or "").lower()
    matched = [version for version in expected if version.lower() in answer]
    return len(matched) / len(expected), matched


def evaluate_quality_gate(case_results: list[dict[str, Any]], thresholds: Thresholds) -> dict[str, Any]:
    faithfulness_avg = average([case["faithfulnessScore"] for case in case_results])
    citation_values = [case["citationAccuracy"] for case in case_results if case["expectedEvidence"]]
    fixed_values = [case["fixedVersionAccuracy"] for case in case_results if case["expectedFixedVersions"]]
    recall_at_8_values = [case.get("recallAt8", 1.0) for case in case_results if case["expectedEvidence"]]
    citation_avg = average(citation_values)
    fixed_avg = average(fixed_values)
    recall_at_8_avg = average(recall_at_8_values)

    failures = []
    if faithfulness_avg < thresholds.faithfulness:
        failures.append("AVERAGE_FAITHFULNESS_BELOW_THRESHOLD")
    if citation_values and citation_avg < thresholds.citation_accuracy:
        failures.append("AVERAGE_CITATION_ACCURACY_BELOW_THRESHOLD")
    if fixed_values and fixed_avg < thresholds.fixed_version_accuracy:
        failures.append("AVERAGE_FIXED_VERSION_ACCURACY_BELOW_THRESHOLD")

    for case in case_results:
        case_id = case.get("caseId")
        if case["expectedEvidence"] and case["citationAccuracy"] == 0:
            failures.append(f"CASE_{case_id}_CITATION_ZERO")
        if case["expectedFixedVersions"] and case["fixedVersionAccuracy"] == 0:
            failures.append(f"CASE_{case_id}_FIXED_VERSION_ZERO")

    return {
        "qualityGatePassed": not failures,
        "failures": failures,
        "caseCount": len(case_results),
        "averageFaithfulness": faithfulness_avg,
        "averageCitationAccuracy": citation_avg,
        "averageFixedVersionAccuracy": fixed_avg,
        "averageRecallAt8": recall_at_8_avg,
    }


def evidence_ids(value: Any) -> set[str]:
    ids = set()
    for item in selected_evidence_items(value):
        if isinstance(item, dict):
            chunk_id = item.get("chunkId")
            file_md5 = item.get("fileMd5")
            if isinstance(chunk_id, str) and ":" in chunk_id:
                ids.add(chunk_id)
            elif file_md5 is not None and chunk_id is not None:
                ids.add(f"{file_md5}:{chunk_id}")
    return ids


def answer_reference_ids(case: dict[str, Any]) -> set[str]:
    mapping = parse_jsonish(case.get("referenceMapping"), {})
    if not isinstance(mapping, dict):
        return set()
    answer = str(case.get("answer") or "")
    ref_numbers = re.findall(r"(?:source|来源)#(\d+)", answer, flags=re.IGNORECASE)
    ids = set()
    for number in ref_numbers:
        value = mapping.get(number) or mapping.get(int(number))
        if isinstance(value, str) and ":" in value:
            ids.add(value)
    return ids


def evidence_texts(value: Any) -> list[str]:
    texts = []
    for item in selected_evidence_items(value):
        if isinstance(item, dict):
            text = str(item.get("textContent") or "").strip()
            if text:
                texts.append(text)
        elif isinstance(item, str) and item.strip():
            texts.append(item.strip())
    return texts


def selected_evidence_items(value: Any) -> list[Any]:
    parsed = parse_jsonish(value, [])
    return parsed if isinstance(parsed, list) else []


def list_values(value: Any) -> list[str]:
    parsed = parse_jsonish(value, [])
    if not isinstance(parsed, list):
        return []
    return [str(item).strip() for item in parsed if str(item).strip()]


def parse_jsonish(value: Any, fallback: Any) -> Any:
    if value is None:
        return fallback
    if isinstance(value, (list, dict)):
        return value
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return fallback
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return fallback
    return fallback


def average(values: list[float]) -> float:
    return sum(values) / len(values) if values else 1.0


def write_artifacts(artifacts_dir: Path, summary: dict[str, Any], cases: list[dict[str, Any]]) -> None:
    artifacts_dir.mkdir(parents=True, exist_ok=True)
    (artifacts_dir / "deepeval-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (artifacts_dir / "deepeval-cases.json").write_text(
        json.dumps(cases, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    write_junit(artifacts_dir / "deepeval-junit.xml", summary, cases)


def write_junit(path: Path, summary: dict[str, Any], cases: list[dict[str, Any]]) -> None:
    failures = summary.get("failures", [])
    testsuite = ElementTree.Element(
        "testsuite",
        {
            "name": "paismart-deepeval",
            "tests": str(max(1, len(cases))),
            "failures": "0" if summary.get("qualityGatePassed") else "1",
            "time": "0",
        },
    )
    testcase = ElementTree.SubElement(testsuite, "testcase", {"name": "quality-gate"})
    if not summary.get("qualityGatePassed"):
        failure = ElementTree.SubElement(testcase, "failure", {"message": ",".join(failures) or "failed"})
        failure.text = json.dumps(summary, ensure_ascii=False, indent=2)
    ElementTree.ElementTree(testsuite).write(path, encoding="utf-8", xml_declaration=True)


def deepseek_judge_model_from_env() -> Any:
    from deepeval.models.base_model import DeepEvalBaseLLM

    class DeepSeekJudgeModel(DeepEvalBaseLLM):
        def __init__(self, api_key: str, base_url: str, model: str):
            self.api_key = api_key
            self.base_url = base_url.rstrip("/")
            self.model = model

        def load_model(self) -> None:
            return None

        def get_model_name(self) -> str:
            return self.model

        def generate(self, prompt: str, schema: Any | None = None) -> Any:
            import requests

            response = requests.post(
                f"{self.base_url}/chat/completions",
                headers={"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"},
                json={
                    "model": self.model,
                    "stream": False,
                    "temperature": 0,
                    "messages": [{"role": "user", "content": prompt}],
                },
                timeout=90,
            )
            response.raise_for_status()
            content = response.json()["choices"][0]["message"]["content"]
            if schema is None:
                return content
            try:
                payload = json.loads(extract_json(content))
                if hasattr(schema, "model_validate"):
                    return schema.model_validate(payload)
                return schema.parse_obj(payload)
            except Exception:
                return content

        async def a_generate(self, prompt: str, schema: Any | None = None) -> Any:
            return self.generate(prompt, schema)

    return DeepSeekJudgeModel(
        api_key=os.environ["DEEPSEEK_API_KEY"],
        base_url=os.getenv("DEEPSEEK_API_URL", "https://api.deepseek.com/v1"),
        model=os.getenv("DEEPSEEK_API_MODEL", "deepseek-chat"),
    )


def extract_json(content: str) -> str:
    text = content.strip()
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    start = text.find("{")
    end = text.rfind("}")
    return text[start:end + 1] if start >= 0 and end >= start else text


if __name__ == "__main__":
    raise SystemExit(main())
