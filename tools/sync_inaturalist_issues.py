#!/usr/bin/env python3
"""Refresh public iNaturalist status on GitHub issues labeled inaturalist-tracking.

This script reads only public observation metadata. It never downloads iNaturalist media and
never marks a recording as approved training data. Human review and contributor licensing remain
separate steps in Stridulate.
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any

MARKER = "<!-- stridulate-inat-sync -->"
OBS_RE = re.compile(r"https?://(?:www\.)?inaturalist\.org/observations/(\d+)", re.I)
API = "https://api.inaturalist.org/v1"


@dataclass
class Snapshot:
    observation_id: int
    url: str
    community_name: str | None
    community_scientific: str | None
    community_rank: str | None
    observer_name: str | None
    quality_grade: str | None
    identifications_count: int
    comments_count: int
    updated_at: str | None

    @property
    def has_community_id(self) -> bool:
        return bool(self.community_scientific)

    @property
    def has_species_level_id(self) -> bool:
        return (self.community_rank or "").lower() in {
            "species", "subspecies", "variety", "form", "hybrid"
        }


def request_json(url: str, *, token: str | None = None, method: str = "GET", payload: Any = None) -> Any:
    headers = {
        "Accept": "application/vnd.github+json" if "api.github.com" in url else "application/json",
        "User-Agent": "Stridulate-community-sync/2.2.1",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
        headers["X-GitHub-Api-Version"] = "2022-11-28"
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=30) as response:
        body = response.read().decode("utf-8")
        return json.loads(body) if body else None


def fetch_inaturalist(observation_id: int) -> Snapshot:
    payload = request_json(f"{API}/observations/{observation_id}")
    results = payload.get("results") or []
    if not results:
        raise RuntimeError(f"iNaturalist observation {observation_id} was not found")
    observation = results[0]
    community = observation.get("community_taxon") or {}
    observer = observation.get("taxon") or {}
    return Snapshot(
        observation_id=observation_id,
        url=f"https://www.inaturalist.org/observations/{observation_id}",
        community_name=community.get("preferred_common_name"),
        community_scientific=community.get("name"),
        community_rank=community.get("rank"),
        observer_name=observer.get("preferred_common_name") or observer.get("name"),
        quality_grade=observation.get("quality_grade"),
        identifications_count=int(observation.get("identifications_count") or 0),
        comments_count=int(observation.get("comments_count") or 0),
        updated_at=observation.get("updated_at"),
    )


def render_comment(snapshot: Snapshot) -> str:
    community = "Awaiting a community taxon"
    if snapshot.community_scientific:
        common = f"{snapshot.community_name} — " if snapshot.community_name else ""
        rank = f" ({snapshot.community_rank})" if snapshot.community_rank else ""
        community = f"**{common}{snapshot.community_scientific}**{rank}"
    return f"""{MARKER}
## iNaturalist status

- **Observation:** {snapshot.url}
- **Community taxon:** {community}
- **Observer's current taxon:** {snapshot.observer_name or 'Not set'}
- **Quality grade:** {snapshot.quality_grade or 'Unavailable'}
- **Activity:** {snapshot.identifications_count} identifications · {snapshot.comments_count} comments
- **iNaturalist updated:** {snapshot.updated_at or 'Unavailable'}

This is a public-status mirror only. A community taxon is evidence, not automatic approval for model training. Stridulate does not download the iNaturalist-hosted sound.
"""


def github_api(repo: str, path: str) -> str:
    return f"https://api.github.com/repos/{repo}{path}"


def sync_issue(repo: str, token: str, issue: dict[str, Any]) -> str:
    body = issue.get("body") or ""
    match = OBS_RE.search(body)
    if not match:
        return f"#{issue['number']}: skipped (no iNaturalist observation URL)"
    observation_id = int(match.group(1))
    snapshot = fetch_inaturalist(observation_id)
    comment_body = render_comment(snapshot)

    comments = request_json(
        github_api(repo, f"/issues/{issue['number']}/comments?per_page=100"), token=token
    )
    existing = next((comment for comment in comments if MARKER in (comment.get("body") or "")), None)
    if existing:
        request_json(
            github_api(repo, f"/issues/comments/{existing['id']}"),
            token=token,
            method="PATCH",
            payload={"body": comment_body},
        )
    else:
        request_json(
            github_api(repo, f"/issues/{issue['number']}/comments"),
            token=token,
            method="POST",
            payload={"body": comment_body},
        )

    labels = {label["name"] for label in issue.get("labels", [])}
    labels.add("inaturalist-tracking")
    labels.discard("needs-id")
    labels.discard("community-id-broad")
    labels.discard("community-id-ready")
    if snapshot.has_species_level_id:
        labels.add("community-id-ready")
    elif snapshot.has_community_id:
        labels.add("community-id-broad")
    else:
        labels.add("needs-id")
    request_json(
        github_api(repo, f"/issues/{issue['number']}"),
        token=token,
        method="PATCH",
        payload={"labels": sorted(labels)},
    )
    state = snapshot.community_scientific or "awaiting community ID"
    return f"#{issue['number']}: {state}"


def ensure_labels(repo: str, token: str) -> None:
    desired = {
        "inaturalist-tracking": ("1d76db", "Public iNaturalist observation linked to a Stridulate recording"),
        "needs-id": ("fbca04", "Still awaiting a community taxon"),
        "community-id-broad": ("d4c5f9", "iNaturalist has a broad taxon, but the observation still needs a species-level ID"),
        "community-id-ready": ("0e8a16", "iNaturalist currently reports a species-level taxon; human review required"),
    }
    existing = request_json(github_api(repo, "/labels?per_page=100"), token=token)
    names = {label["name"] for label in existing}
    for name, (color, description) in desired.items():
        if name not in names:
            request_json(
                github_api(repo, "/labels"),
                token=token,
                method="POST",
                payload={"name": name, "color": color, "description": description},
            )


def main() -> int:
    token = os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY")
    if not token or not repo:
        print("GITHUB_TOKEN and GITHUB_REPOSITORY are required", file=sys.stderr)
        return 2
    ensure_labels(repo, token)
    issues = request_json(
        github_api(repo, "/issues?state=open&labels=inaturalist-tracking&per_page=100"), token=token
    )
    results: list[str] = []
    for issue in issues:
        if "pull_request" in issue:
            continue
        try:
            results.append(sync_issue(repo, token, issue))
        except (urllib.error.URLError, urllib.error.HTTPError, RuntimeError, ValueError) as exc:
            results.append(f"#{issue.get('number', '?')}: ERROR {exc}")
        time.sleep(1.1)

    summary = "\n".join(f"- {line}" for line in results) if results else "- No tracking issues found."
    print(summary)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as handle:
            handle.write("## Stridulate iNaturalist tracking\n\n")
            handle.write(summary + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
