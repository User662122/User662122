#!/usr/bin/env python3
"""Monitor GitHub Actions workflow build status"""
import os
import requests
import time
import sys

GITHUB_TOKEN = os.environ.get('GITHUB_PAT')
OWNER = 'user662122'
REPO = 'user662122'

API_BASE = 'https://api.github.com'
HEADERS = {
    'Authorization': f'Bearer {GITHUB_TOKEN}',
    'Accept': 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28'
}

def get_workflow_runs():
    """Get recent workflow runs"""
    url = f'{API_BASE}/repos/{OWNER}/{REPO}/actions/runs'
    response = requests.get(url, headers=HEADERS, params={'per_page': 5})
    if response.status_code == 200:
        return response.json()['workflow_runs']
    return []

def get_workflow_id(workflow_name='build.yml'):
    """Get workflow ID by name"""
    url = f'{API_BASE}/repos/{OWNER}/{REPO}/actions/workflows'
    response = requests.get(url, headers=HEADERS)
    if response.status_code == 200:
        workflows = response.json()['workflows']
        for workflow in workflows:
            if workflow['path'].endswith(workflow_name):
                return workflow['id']
    return None

def trigger_workflow(workflow_id):
    """Trigger a workflow manually"""
    url = f'{API_BASE}/repos/{OWNER}/{REPO}/actions/workflows/{workflow_id}/dispatches'
    data = {'ref': 'main'}
    response = requests.post(url, headers=HEADERS, json=data)
    return response.status_code == 204

def monitor_latest_run():
    """Monitor the latest workflow run"""
    print("\n🔍 Monitoring latest workflow run...\n")
    
    previous_status = None
    check_count = 0
    max_checks = 60  # 5 minutes max
    
    while check_count < max_checks:
        runs = get_workflow_runs()
        if not runs:
            print("No workflow runs found")
            time.sleep(5)
            check_count += 1
            continue
        
        latest = runs[0]
        status = latest['status']
        conclusion = latest.get('conclusion')
        
        if status != previous_status:
            print(f"Status: {status}")
            if conclusion:
                print(f"Conclusion: {conclusion}")
            previous_status = status
        
        if status == 'completed':
            print(f"\n{'='*60}")
            if conclusion == 'success':
                print("✅ Build SUCCESSFUL!")
                print(f"\n🔗 View run: {latest['html_url']}")
                print(f"📦 Download artifacts from the Actions tab")
                return True
            else:
                print(f"❌ Build FAILED with conclusion: {conclusion}")
                print(f"\n🔗 View logs: {latest['html_url']}")
                return False
        
        time.sleep(5)
        check_count += 1
    
    print("\n⏱️ Timeout reached")
    return None

def main():
    if not GITHUB_TOKEN:
        print("❌ GITHUB_PAT not found")
        return 1
    
    print("=" * 60)
    print("🚀 GitHub Actions Build Monitor")
    print(f"Repository: {OWNER}/{REPO}")
    print("=" * 60)
    
    # Try to trigger the workflow
    print("\n🔧 Checking for workflow...")
    workflow_id = get_workflow_id('build.yml')
    
    if workflow_id:
        print(f"Found workflow ID: {workflow_id}")
        print("\n▶️ Triggering workflow...")
        if trigger_workflow(workflow_id):
            print("✅ Workflow triggered successfully")
            time.sleep(3)  # Wait a bit for workflow to start
        else:
            print("⚠️ Could not trigger workflow (may be already running)")
    else:
        print("⚠️ Workflow not found or not yet pushed")
    
    # Monitor the build
    result = monitor_latest_run()
    
    if result is True:
        return 0
    elif result is False:
        return 1
    else:
        print("\n📊 Check build status at:")
        print(f"https://github.com/{OWNER}/{REPO}/actions")
        return 0

if __name__ == '__main__':
    sys.exit(main())
