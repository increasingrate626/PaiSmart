import json

import pytest

from eval.deepeval import run_deepeval


def test_citation_accuracy_uses_expected_evidence_hits():
    case = {
        "expectedEvidence": ["file-a:7", "file-b:2"],
        "selectedEvidence": [
            {"chunkId": "file-a:7", "textContent": "log4j evidence"},
            {"fileMd5": "file-c", "chunkId": 9, "textContent": "other evidence"},
        ],
        "answer": "Use 2.15.0 (source#1: a.txt)",
    }

    score, matched = run_deepeval.citation_accuracy(case)

    assert score == 0.5
    assert matched == ["file-a:7"]


def test_fixed_version_accuracy_is_case_insensitive():
    case = {
        "expectedFixedVersions": ["2.15.0", "3.0.0"],
        "answer": "The fixed path is LOG4J-CORE 2.15.0.",
    }

    score, matched = run_deepeval.fixed_version_accuracy(case)

    assert score == 0.5
    assert matched == ["2.15.0"]


def test_recall_at_k_uses_only_top_k_selected_evidence():
    case = {
        "expectedEvidence": ["file-a:1", "file-b:2"],
        "selectedEvidence": [
            {"chunkId": "file-a:1", "textContent": "hit in top 1"},
            {"chunkId": "noise:1", "textContent": "noise"},
            {"chunkId": "noise:2", "textContent": "noise"},
            {"chunkId": "noise:3", "textContent": "noise"},
            {"chunkId": "noise:4", "textContent": "noise"},
            {"chunkId": "noise:5", "textContent": "noise"},
            {"chunkId": "noise:6", "textContent": "noise"},
            {"chunkId": "noise:7", "textContent": "noise"},
            {"chunkId": "file-b:2", "textContent": "hit after top 8"},
        ],
        "answer": "The answer cites (source#9: late.txt)",
        "referenceMapping": {"9": "file-b:2"},
    }

    score, matched = run_deepeval.recall_at_k(case, 8)

    assert score == 0.5
    assert matched == ["file-a:1"]


def test_build_test_case_payload_prefers_selected_evidence_text():
    case = {
        "caseId": 1,
        "question": "Is log4j affected?",
        "answer": "Use 2.15.0",
        "finalContext": "[1] fallback",
        "selectedEvidence": [{"textContent": "selected evidence"}],
    }

    payload = run_deepeval.build_test_case_payload(case)

    assert payload["input"] == "Is log4j affected?"
    assert payload["actual_output"] == "Use 2.15.0"
    assert payload["retrieval_context"] == ["selected evidence"]


def test_quality_gate_reports_average_recall_at_8_without_gate_failure():
    summary = run_deepeval.evaluate_quality_gate(
        [
            {
                "caseId": 1,
                "faithfulnessScore": 1.0,
                "citationAccuracy": 1.0,
                "fixedVersionAccuracy": 1.0,
                "recallAt8": 0.5,
                "expectedEvidence": ["file-a:1"],
                "expectedFixedVersions": [],
            },
            {
                "caseId": 2,
                "faithfulnessScore": 1.0,
                "citationAccuracy": 1.0,
                "fixedVersionAccuracy": 1.0,
                "recallAt8": 1.0,
                "expectedEvidence": ["file-b:2"],
                "expectedFixedVersions": [],
            },
        ],
        run_deepeval.Thresholds(),
    )

    assert summary["qualityGatePassed"] is True
    assert summary["averageRecallAt8"] == 0.75


def test_quality_gate_fails_on_average_threshold_and_zero_case_metric():
    summary = run_deepeval.evaluate_quality_gate(
        [
            {
                "caseId": 1,
                "faithfulnessScore": 0.9,
                "citationAccuracy": 0.0,
                "fixedVersionAccuracy": 1.0,
                "expectedEvidence": ["file-a:7"],
                "expectedFixedVersions": ["2.15.0"],
            },
            {
                "caseId": 2,
                "faithfulnessScore": 0.9,
                "citationAccuracy": 1.0,
                "fixedVersionAccuracy": 1.0,
                "expectedEvidence": ["file-b:1"],
                "expectedFixedVersions": [],
            },
        ],
        run_deepeval.Thresholds(faithfulness=0.95, citation_accuracy=0.80, fixed_version_accuracy=0.80),
    )

    assert summary["qualityGatePassed"] is False
    assert "AVERAGE_FAITHFULNESS_BELOW_THRESHOLD" in summary["failures"]
    assert "CASE_1_CITATION_ZERO" in summary["failures"]


def test_missing_required_keys_marks_skip(monkeypatch, tmp_path):
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)

    exit_code = run_deepeval.main(["--artifacts-dir", str(tmp_path)])

    assert exit_code == 0
    summary = json.loads((tmp_path / "deepeval-summary.json").read_text(encoding="utf-8"))
    assert summary["skipped"] is True
    assert "DEEPSEEK_API_KEY" in summary["missingKeys"]
    assert "DASHSCOPE_API_KEY" in summary["missingKeys"]


def test_threshold_failure_returns_nonzero_without_deepeval(monkeypatch, tmp_path):
    export = {
        "run": {"id": 1, "totalCases": 1},
        "cases": [
            {
                "caseId": 1,
                "question": "Is log4j affected?",
                "answer": "No citation",
                "finalContext": "[1] log4j-core is fixed in 2.15.0",
                "selectedEvidence": [],
                "expectedEvidence": ["file-a:7"],
                "expectedFixedVersions": ["2.15.0"],
            }
        ],
    }
    export_path = tmp_path / "export.json"
    export_path.write_text(json.dumps(export), encoding="utf-8")
    monkeypatch.setenv("DEEPSEEK_API_KEY", "deepseek")
    monkeypatch.setenv("DASHSCOPE_API_KEY", "dashscope")

    exit_code = run_deepeval.main([
        "--input-json",
        str(export_path),
        "--skip-faithfulness",
        "--artifacts-dir",
        str(tmp_path),
    ])

    assert exit_code == 1
    summary = json.loads((tmp_path / "deepeval-summary.json").read_text(encoding="utf-8"))
    assert summary["qualityGatePassed"] is False
    assert "CASE_1_CITATION_ZERO" in summary["failures"]
