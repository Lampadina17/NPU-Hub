#!/usr/bin/env python3
import sys
import os
import json
import re
import urllib.request
import urllib.parse

def download_file(url, target_path):
    os.makedirs(os.path.dirname(target_path), exist_ok=True)
    print(f"Downloading {url} -> {target_path}...", flush=True)
    partial_path = f"{target_path}.part"

    # Request with user-agent
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req) as response, open(partial_path, 'wb') as out_file:
        total_length = response.getheader('content-length')
        if total_length:
            total_length = int(total_length)
            dl = 0
            while True:
                buffer = response.read(1024 * 1024)
                if not buffer:
                    break
                dl += len(buffer)
                out_file.write(buffer)
                percent = int((dl / total_length) * 100)
                sys.stdout.write(f"\rPROGRESS:{percent}% ({dl}/{total_length} bytes)")
                sys.stdout.flush()
            print("")
        else:
            out_file.write(response.read())
    os.replace(partial_path, target_path)

def matches_quantization(filename, quantization):
    pattern = rf"(?<![A-Z0-9]){re.escape(quantization.upper())}(?![A-Z0-9_])"
    return re.search(pattern, os.path.basename(filename).upper()) is not None

def select_quantization(files, quantization):
    if not quantization:
        return files

    selected = [
        filename for filename in files
        if filename.lower().endswith(".gguf")
        and matches_quantization(filename, quantization)
    ]
    if selected:
        return selected

    available = sorted(
        os.path.basename(filename)
        for filename in files
        if filename.lower().endswith(".gguf")
    )
    raise RuntimeError(
        f"Quantization {quantization} is not available. "
        f"GGUF files in the repository: {', '.join(available) or 'none'}"
    )

def download_huggingface(repo_id, target_dir, quantization=None):
    print(f"Fetching HuggingFace model metadata for {repo_id}...", flush=True)
    api_url = f"https://huggingface.co/api/models/{repo_id}"
    req = urllib.request.Request(api_url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode())
    
    files = [s["rfilename"] for s in data.get("siblings", [])]
    files = select_quantization(files, quantization)
    total_files = len(files)
    
    for idx, f in enumerate(files):
        url = f"https://huggingface.co/{repo_id}/resolve/main/{f}"
        out_path = os.path.join(target_dir, f)
        print(f"[{idx+1}/{total_files}] Downloading {f}...", flush=True)
        download_file(url, out_path)

def download_modelscope(repo_id, target_dir, quantization=None):
    print(f"Fetching ModelScope model metadata for {repo_id}...", flush=True)
    full_repo = repo_id if "/" in repo_id else f"radxa/{repo_id}"
    # Without Recursive=true ModelScope returns only the repository's top-level
    # entries, which can omit the large weight files stored below subdirectories.
    api_url = f"https://www.modelscope.cn/api/v1/models/{full_repo}/repo/files?Revision=master&Recursive=true"
    req = urllib.request.Request(api_url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode())
    
    files = [item["Path"] for item in data.get("Data", {}).get("Files", []) if item.get("Type") == "blob"]
    files = select_quantization(files, quantization)
    total_files = len(files)
    
    for idx, f in enumerate(files):
        url = f"https://www.modelscope.cn/models/{full_repo}/resolve/master/{f}"
        out_path = os.path.join(target_dir, f)
        print(f"[{idx+1}/{total_files}] Downloading {f}...", flush=True)
        download_file(url, out_path)

def main():
    if len(sys.argv) < 3:
        print("Usage: download_model.py <model_id> <target_directory> [quantization]")
        sys.exit(1)
        
    model_id = sys.argv[1]
    target_dir = sys.argv[2]
    quantization = sys.argv[3].upper() if len(sys.argv) > 3 else None
    
    if model_id.startswith("OpenVINO/") or model_id.startswith("unsloth/"):
        download_huggingface(model_id, target_dir, quantization)
    else:
        download_modelscope(model_id, target_dir, quantization)
        
    selection = quantization or "all repository files"
    print(f"COMPLETE: Downloaded {selection} for {model_id} into {target_dir}", flush=True)

if __name__ == "__main__":
    main()
