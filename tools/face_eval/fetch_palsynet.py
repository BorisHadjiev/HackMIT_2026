#!/usr/bin/env python3
"""Download a balanced, subject-stratified subset of the PalsyNet-5230 dataset.

Repo: https://huggingface.co/datasets/lili3329/palsynet-5230
Layout: frames/{affected,unaffected}/<subject>/<frame>.jpg
"""
import argparse
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from huggingface_hub import HfApi, hf_hub_download
from tqdm import tqdm

REPO = "lili3329/palsynet-5230"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-class", type=int, default=600)
    ap.add_argument("--out", default=str(Path.home() / "face_eval/palsynet"))
    args = ap.parse_args()
    out = Path(args.out)

    api = HfApi()
    info = api.dataset_info(REPO, files_metadata=True)
    groups = defaultdict(list)
    for s in info.siblings:
        p = s.rfilename
        if not p.endswith(".jpg"):
            continue
        parts = p.split("/")
        if len(parts) < 4 or parts[0] != "frames":
            continue
        groups[(parts[1], parts[2])].append(p)

    by_class = defaultdict(list)
    for (cls, subj), files in groups.items():
        by_class[cls].append(files)

    chosen = []
    for cls, file_lists in by_class.items():
        n_groups = len(file_lists)
        per_group = max(1, args.per_class // n_groups)
        for files in file_lists:
            step = max(1, len(files) // per_group)
            chosen.extend(files[::step][:per_group])
        print(f"{cls}: {n_groups} subjects, {per_group}/subject")

    out.mkdir(parents=True, exist_ok=True)
    print(f"downloading {len(chosen)} files to {out}")

    def get(path):
        return hf_hub_download(REPO, path, repo_type="dataset", local_dir=str(out))

    ok = 0
    with ThreadPoolExecutor(max_workers=8) as ex:
        futures = [ex.submit(get, p) for p in chosen]
        for f in tqdm(as_completed(futures), total=len(futures)):
            try:
                f.result()
                ok += 1
            except Exception as e:  # noqa: BLE001
                print("fail:", e)
    print(f"downloaded {ok}/{len(chosen)}")


if __name__ == "__main__":
    main()
