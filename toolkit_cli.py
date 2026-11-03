#!/usr/bin/env python3
"""
CLI Interface for GitHub Actions & Codespaces Toolkit
Entry point: minis-gh-toolkit
"""

import os
import sys
import json
import asyncio
import logging
from pathlib import Path
from typing import Optional, List, Dict, Any

import click
from click.shell_completion import CompletionItem

# Add pipeline scripts to path
pipeline_path = Path(__file__).parent / "android_pipeline"
if str(pipeline_path) not in sys.path:
    sys.path.insert(0, str(pipeline_path))

from config import get_config, validate_config, run_setup_wizard, reload_config
from security_utils import (
    validate_github_repo, validate_workflow_name, validate_run_id,
    validate_branch_name, validate_file_path, validate_output_dir,
    validate_apk_path, validate_error_type, sanitize_workflow_inputs,
    safe_run, mask_secrets, SecurityError, ValidationError
)

# ============================================================================
# CLI CONTEXT & HELPERS
# ============================================================================

class CLIContext:
    """CLI context holding configuration and state"""
    def __init__(self):
        self.config = None
        self.verbose = False
        self.dry_run = False
        self.json_output = False
    
    def load_config(self, config_file: Optional[str] = None):
        self.config = get_config(config_file)
        return self.config


pass_context = click.make_pass_decorator(CLIContext, ensure=True)


def setup_logging(verbose: bool, json_format: bool = False):
    """Configure logging"""
    level = logging.DEBUG if verbose else logging.INFO
    
    if json_format:
        import pythonjsonlogger.jsonlogger
        formatter = pythonjsonlogger.jsonlogger.JsonFormatter(
            '%(timestamp)s %(level)s %(name)s %(message)s'
        )
    else:
        formatter = logging.Formatter(
            '%(asctime)s | %(levelname)-8s | %(name)s | %(message)s',
            datefmt='%H:%M:%S'
        )
    
    handler = logging.StreamHandler()
    handler.setFormatter(formatter)
    
    root_logger = logging.getLogger()
    root_logger.setLevel(level)
    root_logger.handlers = [handler]
    
    # Reduce noise from libraries
    logging.getLogger("urllib3").setLevel(logging.WARNING)
    logging.getLogger("github").setLevel(logging.WARNING)


def print_json(data: Any):
    """Print JSON output"""
    click.echo(json.dumps(data, indent=2, default=str, ensure_ascii=False))


def print_error(message: str, code: str = "ERROR"):
    """Print error message"""
    click.echo(click.style(f"[{code}] {message}", fg="red"), err=True)


def print_success(message: str):
    """Print success message"""
    click.echo(click.style(f"✓ {message}", fg="green"))


def print_warning(message: str):
    """Print warning message"""
    click.echo(click.style(f"⚠ {message}", fg="yellow"))


# ============================================================================
# COMPLETION HELPERS
# ============================================================================

def complete_github_repo(ctx, param, incomplete):
    """Autocomplete GitHub repos (would need API call, return cached)"""
    # Could be enhanced with actual API call
    return []


def complete_workflow_file(ctx, param, incomplete):
    """Autocomplete workflow files"""
    return [CompletionItem(f"{w}.yml", help=f"Workflow file") for w in [
        "android-production-pipeline", "build", "test", "release", "ci"
    ] if w.startswith(incomplete)]


def complete_error_type(ctx, param, incomplete):
    """Autocomplete error types for auto_fix_build"""
    error_types = [
        "missing_permission", "permission_denied",
        "gradle_lock", "lock_error",
        "dexing_d8_r8", "proguard_missing",
        "sdk_license", "license_not_accepted",
        "aapt2_error", "kotlinc_error", "d8_error",
        "gomobile_error", "manifest_error",
    ]
    return [CompletionItem(t) for t in error_types if t.startswith(incomplete)]


# ============================================================================
# MAIN CLI GROUP
# ============================================================================

@click.group()
@click.option('--config', '-c', 'config_file', type=click.Path(exists=True),
              help='Path to configuration YAML file')
@click.option('--verbose', '-v', is_flag=True, help='Enable verbose output')
@click.option('--dry-run', is_flag=True, help='Show what would be done without executing')
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@click.option('--debug', is_flag=True, help='Enable debug logging')
@click.version_option(version='2.6.0', prog_name='minis-gh-toolkit')
@pass_context
def cli(ctx: CLIContext, config_file: Optional[str], verbose: bool, 
        dry_run: bool, json_output: bool, debug: bool):
    """GitHub Actions & Codespaces Toolkit for Minis AI Agent
    
    Orchestrate Android builds, Codespaces, and CI/CD workflows
    across local (Minis PRoot) and cloud (GitHub Actions/Codespaces) environments.
    """
    ctx.verbose = verbose or debug
    ctx.dry_run = dry_run
    ctx.json_output = json_output
    
    setup_logging(ctx.verbose, json_output)
    ctx.load_config(config_file)
    
    if debug:
        logging.getLogger().setLevel(logging.DEBUG)


@cli.command()
@pass_context
def wizard(ctx: CLIContext):
    """Interactive setup wizard for first-time configuration"""
    config = run_setup_wizard()
    if ctx.json_output:
        print_json({
            "environment": config.environment,
            "github_api": config.github.api_url,
            "android_sdk": config.android.sdk_root,
            "vault_backend": config.vault.backend,
        })


@cli.command()
@click.option('--path', '-p', type=click.Path(), default='config.example.yaml',
              help='Output path for example config')
@pass_context
def config_example(ctx: CLIContext, path: str):
    """Generate example configuration file"""
    from config import ConfigLoader
    loader = ConfigLoader()
    loader.save_example(path)
    print_success(f"Example config saved to: {path}")


@cli.command()
@click.option('--config-file', '-c', type=click.Path(exists=True),
              help='Config file to validate')
@pass_context
def validate(ctx: CLIContext, config_file: Optional[str]):
    """Validate configuration"""
    if config_file:
        reload_config(config_file)
    config = ctx.config
    issues = validate_config(config)
    
    if ctx.json_output:
        print_json({
            "valid": len(issues) == 0,
            "issues": issues
        })
    else:
        if issues:
            for issue in issues:
                if issue.startswith("ERROR"):
                    print_error(issue)
                else:
                    print_warning(issue)
            sys.exit(1)
        else:
            print_success("Configuration is valid")


@cli.command()
@pass_context
def doctor(ctx: CLIContext):
    """Run diagnostic checks"""
    config = ctx.config
    results = {
        "github_cli": False,
        "git": False,
        "android_sdk": False,
        "config_valid": False,
        "memory_db": False,
    }
    
    # Check gh CLI
    try:
        result = safe_run(["gh", "auth", "status"])
        results["github_cli"] = result.returncode == 0
    except Exception:
        pass
    
    # Check git
    try:
        result = safe_run(["git", "--version"])
        results["git"] = result.returncode == 0
    except Exception:
        pass
    
    # Check Android SDK
    sdk_path = Path(config.android.sdk_root).expanduser()
    results["android_sdk"] = sdk_path.exists()
    
    # Validate config
    issues = validate_config(config)
    results["config_valid"] = len([i for i in issues if i.startswith("ERROR")]) == 0
    
    # Check memory DB
    db_path = Path(config.memory.db_path).expanduser()
    results["memory_db"] = db_path.parent.exists()
    
    if ctx.json_output:
        print_json(results)
    else:
        for check, passed in results.items():
            status = click.style("✓ PASS", fg="green") if passed else click.style("✗ FAIL", fg="red")
            click.echo(f"  {check}: {status}")
        
        if not all(results.values()):
            sys.exit(1)


# ============================================================================
# GITHUB ACTIONS COMMANDS
# ============================================================================

@cli.group()
def workflows():
    """GitHub Actions workflow management"""
    pass


@workflows.command('list')
@click.argument('repo', required=True, shell_complete=complete_github_repo)
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def workflows_list(ctx: CLIContext, repo: str, json_output: bool):
    """List workflows for a repository"""
    try:
        repo = validate_github_repo(repo)
        result = safe_run(["gh", "workflow", "list", "--repo", repo, "--json", "id,name,state,path"])
        data = json.loads(result.stdout)
        if json_output or ctx.json_output:
            print_json(data)
        else:
            for wf in data:
                status = click.style(wf['state'], fg="green" if wf['state'] == 'active' else "yellow")
                click.echo(f"  {wf['name']} ({wf['id']}) - {status} - {wf['path']}")
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


@workflows.command('trigger')
@click.argument('repo', required=True, shell_complete=complete_github_repo)
@click.argument('workflow', required=True, shell_complete=complete_workflow_file)
@click.option('--ref', '-r', default='main', help='Git ref (branch/tag)')
@click.option('--input', '-i', 'inputs', multiple=True, help='Input as key=value')
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def workflows_trigger(ctx: CLIContext, repo: str, workflow: str, ref: str, inputs: tuple, json_output: bool):
    """Trigger a workflow dispatch"""
    try:
        repo = validate_github_repo(repo)
        workflow = validate_workflow_name(workflow)
        ref = validate_branch_name(ref)
        
        input_dict = {}
        for inp in inputs:
            if '=' in inp:
                k, v = inp.split('=', 1)
                input_dict[k] = v
        
        input_dict = sanitize_workflow_inputs(json.dumps(input_dict))
        
        if ctx.dry_run:
            print_warning(f"DRY RUN: Would trigger {workflow} on {repo}@{ref} with inputs: {input_dict}")
            return
        
        cmd = ["gh", "workflow", "run", workflow, "--repo", repo, "--ref", ref]
        for k, v in input_dict.items():
            cmd.extend(["-f", f"{k}={v}"])
        
        result = safe_run(cmd)
        if json_output or ctx.json_output:
            print_json({"triggered": True, "workflow": workflow, "repo": repo, "ref": ref})
        else:
            print_success(f"Workflow triggered: {workflow} on {repo}@{ref}")
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


@workflows.command('runs')
@click.argument('repo', required=True, shell_complete=complete_github_repo)
@click.option('--workflow', '-w', help='Filter by workflow name')
@click.option('--limit', '-l', default=10, help='Max number of runs')
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def workflows_runs(ctx: CLIContext, repo: str, workflow: Optional[str], limit: int, json_output: bool):
    """List recent workflow runs"""
    try:
        repo = validate_github_repo(repo)
        if workflow:
            workflow = validate_workflow_name(workflow)
        if limit < 1 or limit > 100:
            limit = 10
        
        cmd = ["gh", "run", "list", "--repo", repo, "--limit", str(limit), "--json",
               "databaseId,name,status,conclusion,createdAt,workflowName,headBranch"]
        if workflow:
            cmd.extend(["--workflow", workflow])
        
        result = safe_run(cmd)
        data = json.loads(result.stdout)
        if json_output or ctx.json_output:
            print_json(data)
        else:
            for run in data:
                status_color = "green" if run['conclusion'] == 'success' else "red" if run['conclusion'] == 'failure' else "yellow"
                status = click.style(run['conclusion'] or run['status'], fg=status_color)
                click.echo(f"  #{run['databaseId']} {run['workflowName']} - {status} - {run['headBranch']} - {run['createdAt']}")
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


@workflows.command('log')
@click.argument('run_id', required=True)
@click.argument('repo', required=True, shell_complete=complete_github_repo)
@click.option('--failed-only/--full', default=True, help='Show only failed steps')
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def workflows_log(ctx: CLIContext, run_id: str, repo: str, failed_only: bool, json_output: bool):
    """View workflow run logs"""
    try:
        repo = validate_github_repo(repo)
        run_id = validate_run_id(run_id)
        
        cmd = ["gh", "run", "view", run_id, "--repo", repo]
        if failed_only:
            cmd.append("--log-failed")
        
        result = safe_run(cmd)
        if json_output or ctx.json_output:
            print_json({"log": result.stdout, "run_id": run_id, "repo": repo})
        else:
            click.echo(result.stdout)
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


# ============================================================================
# CODESPACES COMMANDS
# ============================================================================

@cli.group()
def codespaces():
    """GitHub Codespaces management"""
    pass


@codespaces.command('list')
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def codespaces_list(ctx: CLIContext, json_output: bool):
    """List active Codespaces"""
    try:
        result = safe_run(["gh", "codespace", "list", "--json", "name,repository,state,branch,createdAt,machine"])
        data = json.loads(result.stdout)
        if json_output or ctx.json_output:
            print_json(data)
        else:
            for cs in data:
                state_color = "green" if cs['state'] == 'Running' else "yellow"
                state = click.style(cs['state'], fg=state_color)
                click.echo(f"  {cs['name']} ({cs['repository']}) - {state} - {cs['machine']} - {cs['branch']}")
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


@codespaces.command('create')
@click.argument('repo', required=True, shell_complete=complete_github_repo)
@click.option('--branch', '-b', help='Branch to use')
@click.option('--machine', '-m', help='Machine type')
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def codespaces_create(ctx: CLIContext, repo: str, branch: Optional[str], machine: Optional[str], json_output: bool):
    """Create a new Codespace"""
    try:
        repo = validate_github_repo(repo)
        if branch:
            branch = validate_branch_name(branch)
        
        if ctx.dry_run:
            print_warning(f"DRY RUN: Would create codespace for {repo}@{branch or 'default'}")
            return
        
        cmd = ["gh", "codespace", "create", "--repo", repo]
        if branch:
            cmd.extend(["--branch", branch])
        if machine:
            cmd.extend(["--machine", machine])
        
        result = safe_run(cmd)
        if json_output or ctx.json_output:
            print_json({"created": True, "repo": repo, "branch": branch})
        else:
            print_success(f"Codespace created for {repo}")
            click.echo(result.stdout)
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


@codespaces.command('delete')
@click.argument('codespace', required=True)
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def codespaces_delete(ctx: CLIContext, codespace: str, json_output: bool):
    """Delete a Codespace"""
    try:
        if ctx.dry_run:
            print_warning(f"DRY RUN: Would delete codespace {codespace}")
            return
        
        result = safe_run(["gh", "codespace", "delete", "--codespace", codespace, "--confirm"])
        if json_output or ctx.json_output:
            print_json({"deleted": True, "codespace": codespace})
        else:
            print_success(f"Codespace deleted: {codespace}")
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


# ============================================================================
# ANDROID PIPELINE COMMANDS
# ============================================================================

@cli.group()
def android():
    """Android build pipeline management"""
    pass


@android.command('verify')
@click.argument('project_path', type=click.Path(exists=True), default='.')
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def android_verify(ctx: CLIContext, project_path: str, json_output: bool):
    """Pre-flight check for Android project"""
    try:
        project_path = str(validate_file_path(project_path, must_exist=True))
        
        # Import and run verification
        sys.path.insert(0, str(pipeline_path))
        from dependency_graph import AndroidProjectAnalyzer
        
        analyzer = AndroidProjectAnalyzer(project_path)
        report = analyzer.check_prerequisites()
        
        if json_output or ctx.json_output:
            print_json(report)
        else:
            click.echo(f"Project: {project_path}")
            for key, value in report.items():
                status = "✓" if value else "✗"
                click.echo(f"  {status} {key}: {value}")
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


@android.command('diagnose')
@click.argument('repo', required=True, shell_complete=complete_github_repo)
@click.argument('run_id', required=True)
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def android_diagnose(ctx: CLIContext, repo: str, run_id: str, json_output: bool):
    """Diagnose build failure from workflow run"""
    try:
        repo = validate_github_repo(repo)
        run_id = validate_run_id(run_id)
        
        cmd = ["gh", "run", "view", run_id, "--repo", repo, "--log-failed"]
        result = safe_run(cmd)
        
        sys.path.insert(0, str(pipeline_path))
        from build_monitor import AndroidBuildDiagnostics
        diagnostics = AndroidBuildDiagnostics.analyze_log(result.stdout or result.stderr)
        
        if json_output or ctx.json_output:
            print_json(diagnostics)
        else:
            click.echo(f"Run: {run_id} on {repo}")
            for key, value in diagnostics.items():
                click.echo(f"  {key}: {value}")
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


@android.command('artifacts')
@click.argument('repo', required=True, shell_complete=complete_github_repo)
@click.argument('run_id', required=True)
@click.option('--output-dir', '-o', type=click.Path(), default='/var/minis/workspace/artifacts')
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def android_artifacts(ctx: CLIContext, repo: str, run_id: str, output_dir: str, json_output: bool):
    """Download build artifacts (APKs/AABs)"""
    try:
        repo = validate_github_repo(repo)
        run_id = validate_run_id(run_id)
        output_dir = str(validate_output_dir(output_dir))
        
        if ctx.dry_run:
            print_warning(f"DRY RUN: Would download artifacts from {repo} run {run_id} to {output_dir}")
            return
        
        cmd = ["gh", "run", "download", run_id, "--repo", repo, "--dir", output_dir]
        result = safe_run(cmd)
        
        if json_output or ctx.json_output:
            print_json({"downloaded": True, "output_dir": output_dir})
        else:
            print_success(f"Artifacts downloaded to {output_dir}")
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


@android.command('profile')
@click.argument('apk_path', type=click.Path(exists=True))
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def android_profile(ctx: CLIContext, apk_path: str, json_output: bool):
    """Profile built APK (size, DEX count, native libs, assets)"""
    try:
        apk_path = str(validate_apk_path(apk_path))
        
        sys.path.insert(0, str(pipeline_path))
        from apk_profiler import APKProfiler
        report = APKProfiler.profile_apk(apk_path)
        
        if json_output or ctx.json_output:
            print_json(report)
        else:
            click.echo(f"APK Profile: {apk_path}")
            for key, value in report.items():
                click.echo(f"  {key}: {value}")
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


@android.command('autofix')
@click.argument('error_type', required=True, shell_complete=complete_error_type)
@click.argument('project_path', type=click.Path(exists=True), default='.')
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def android_autofix(ctx: CLIContext, error_type: str, project_path: str, json_output: bool):
    """Apply automated self-healing patch for build error"""
    try:
        error_type = validate_error_type(error_type)
        project_path = str(validate_file_path(project_path, must_exist=True))
        
        sys.path.insert(0, str(pipeline_path))
        from auto_fixer import AutoFixEngine
        
        engine = AutoFixEngine(project_path)
        fixes = engine.apply_patch_for_error(error_type)
        
        if not fixes:
            msg = "No automated patch available for this error type"
            if json_output or ctx.json_output:
                print_json({"success": False, "message": msg})
            else:
                print_warning(msg)
            return
        
        if ctx.dry_run:
            print_warning(f"DRY RUN: Would apply fixes: {fixes}")
            if json_output or ctx.json_output:
                print_json({"dry_run": True, "would_apply": fixes})
            return
        
        ok, msg = engine.commit_and_push(f"fix(autofix): self-healing patch for {error_type}")
        
        result = {"applied_fixes": fixes, "push_status": msg, "success": ok}
        if json_output or ctx.json_output:
            print_json(result)
        else:
            if ok:
                print_success(f"Applied {len(fixes)} fixes and pushed")
            else:
                print_error(f"Fixes applied but push failed: {msg}")
                sys.exit(1)
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


@android.command('sbom')
@click.argument('project_path', type=click.Path(exists=True), default='.')
@click.option('--format', '-f', type=click.Choice(['json', 'xml']), default='json')
@click.option('--output', '-o', type=click.Path(), help='Output file path')
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def android_sbom(ctx: CLIContext, project_path: str, format: str, output: Optional[str], json_output: bool):
    """Generate CycloneDX SBOM for project"""
    try:
        project_path = str(validate_file_path(project_path, must_exist=True))
        
        sys.path.insert(0, str(pipeline_path))
        from sbom_generator import SBOMGenerator
        
        gen = SBOMGenerator(project_path)
        if output:
            gen.output_path = Path(output)
        
        ok, path = gen.generate_sbom(format)
        
        if json_output or ctx.json_output:
            print_json({"success": ok, "path": path})
        else:
            if ok:
                print_success(f"SBOM generated: {path}")
            else:
                print_error(f"SBOM generation failed: {path}")
                sys.exit(1)
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


@android.command('changelog')
@click.argument('version_tag', required=True)
@click.argument('project_path', type=click.Path(exists=True), default='.')
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def android_changelog(ctx: CLIContext, version_tag: str, project_path: str, json_output: bool):
    """Generate CHANGELOG.md from git history"""
    try:
        project_path = str(validate_file_path(project_path, must_exist=True))
        
        sys.path.insert(0, str(pipeline_path))
        from changelog_automator import ChangelogAutomator
        
        automator = ChangelogAutomator(project_path)
        ok, path = automator.generate_changelog(version_tag)
        
        if json_output or ctx.json_output:
            print_json({"success": ok, "changelog_path": path})
        else:
            if ok:
                print_success(f"Changelog updated: {path}")
            else:
                print_error(f"Changelog generation failed: {path}")
                sys.exit(1)
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


# ============================================================================
# SECURITY COMMANDS
# ============================================================================

@cli.group()
def security():
    """Security and ACL management"""
    pass


@security.command('check')
@click.option('--command', '-c', help='Command to validate')
@click.option('--path', '-p', help='File path to validate')
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def security_check(ctx: CLIContext, command: Optional[str], path: Optional[str], json_output: bool):
    """Validate command or path against security ACL"""
    try:
        from security_acl import AgentSecurityACL
        results = {}
        if command:
            ok, msg = AgentSecurityACL.validate_command(command)
            results["command_check"] = {"allowed": ok, "reason": msg}
        if path:
            ok, msg = AgentSecurityACL.validate_file_path(path)
            results["path_check"] = {"allowed": ok, "reason": msg}
        
        if json_output or ctx.json_output:
            print_json(results)
        else:
            for key, value in results.items():
                status = "✓" if value["allowed"] else "✗"
                click.echo(f"  {status} {key}: {value['reason']}")
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


@security.command('encrypt')
@click.argument('secret_text')
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def security_encrypt(ctx: CLIContext, secret_text: str, json_output: bool):
    """Encrypt secret using AES-256 Fernet"""
    try:
        sys.path.insert(0, str(pipeline_path))
        from vault_crypto import VaultCryptoEngine
        
        engine = VaultCryptoEngine()
        encrypted = engine.encrypt_secret(secret_text)
        
        if json_output or ctx.json_output:
            print_json({"encrypted": encrypted})
        else:
            click.echo(encrypted)
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


@security.command('vault')
@click.argument('action', type=click.Choice(['get', 'set', 'list', 'delete']))
@click.argument('secret_name', required=False)
@click.argument('secret_value', required=False)
@click.option('--backend', '-b', type=click.Choice(['local', 'aws_kms', 'hashicorp_vault', 'azure_keyvault', 'gcp_secretmanager']), default='local')
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def security_vault(ctx: CLIContext, action: str, secret_name: Optional[str], secret_value: Optional[str], backend: str, json_output: bool):
    """Manage secrets in vault"""
    try:
        from vault_adapter import EnterpriseVaultManager
        
        mgr = EnterpriseVaultManager(backend=backend)
        
        if action == 'get':
            if not secret_name:
                print_error("Secret name required for get")
                sys.exit(1)
            val = mgr.resolve_secret(secret_name)
            if json_output or ctx.json_output:
                print_json({"found": bool(val), "value": val})
            else:
                if val:
                    click.echo(val)
                else:
                    print_warning(f"Secret not found: {secret_name}")
        
        elif action == 'set':
            if not secret_name or not secret_value:
                print_error("Secret name and value required for set")
                sys.exit(1)
            ok = mgr.store_secret(secret_name, secret_value)
            if json_output or ctx.json_output:
                print_json({"success": ok})
            else:
                if ok:
                    print_success(f"Secret stored: {secret_name}")
                else:
                    print_error("Failed to store secret")
                    sys.exit(1)
        
        elif action == 'list':
            secrets = mgr.list_secrets(secret_name or "")
            if json_output or ctx.json_output:
                print_json({"secrets": secrets})
            else:
                for s in secrets:
                    click.echo(f"  {s}")
        
        elif action == 'delete':
            if not secret_name:
                print_error("Secret name required for delete")
                sys.exit(1)
            ok = mgr.delete_secret(secret_name)
            if json_output or ctx.json_output:
                print_json({"success": ok})
            else:
                if ok:
                    print_success(f"Secret deleted: {secret_name}")
                else:
                    print_error("Failed to delete secret")
                    sys.exit(1)
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


# ============================================================================
# MEMORY COMMANDS
# ============================================================================

@cli.group()
def memory():
    """Pipeline memory management"""
    pass


@memory.command('save')
@click.argument('type', type=click.Choice(['compilation', 'packaging', 'transient', 'persistent']))
@click.argument('key')
@click.argument('value')
@click.option('--category', help='Category for persistent records')
@click.option('--ttl', type=int, help='TTL in seconds for transient cache')
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def memory_save(ctx: CLIContext, type: str, key: str, value: str, category: Optional[str], ttl: Optional[int], json_output: bool):
    """Save data to pipeline memory"""
    try:
        from sqlite_memory_manager import SQLiteMemoryManager
        
        mgr = SQLiteMemoryManager()
        data = json.loads(value)
        
        if type == 'compilation':
            ok = mgr.save_compilation_state(key, data)
        elif type == 'packaging':
            ok = mgr.save_packaging_record(key, data)
        elif type == 'transient':
            ok = mgr.save_transient_cache(key, data, ttl)
        elif type == 'persistent':
            ok = mgr.save_persistent_record(key, data, category or 'general')
        
        if json_output or ctx.json_output:
            print_json({"success": ok})
        else:
            if ok:
                print_success(f"Saved to {type} memory: {key}")
            else:
                print_error("Failed to save")
                sys.exit(1)
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)
    except json.JSONDecodeError:
        print_error("Value must be valid JSON")
        sys.exit(1)


@memory.command('get')
@click.argument('type', type=click.Choice(['compilation', 'packaging', 'transient', 'persistent']))
@click.argument('key')
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def memory_get(ctx: CLIContext, type: str, key: str, json_output: bool):
    """Get data from pipeline memory"""
    try:
        from sqlite_memory_manager import SQLiteMemoryManager
        
        mgr = SQLiteMemoryManager()
        
        if type == 'compilation':
            val = mgr.get_compilation_state(key)
        elif type == 'packaging':
            val = mgr.get_packaging_record(key)
        elif type == 'transient':
            val = mgr.get_transient_cache(key)
        elif type == 'persistent':
            val = mgr.get_persistent_record(key)
        
        if json_output or ctx.json_output:
            print_json({"found": val is not None, "value": val})
        else:
            if val is not None:
                click.echo(json.dumps(val, indent=2))
            else:
                print_warning(f"Not found: {key}")
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


@memory.command('stats')
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def memory_stats(ctx: CLIContext, json_output: bool):
    """Show memory database statistics"""
    try:
        from sqlite_memory_manager import SQLiteMemoryManager
        
        mgr = SQLiteMemoryManager()
        stats = mgr.get_stats()
        
        if json_output or ctx.json_output:
            print_json(stats)
        else:
            click.echo("Memory Database Statistics:")
            for key, value in stats.items():
                click.echo(f"  {key}: {value}")
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


# ============================================================================
# UTILITY COMMANDS
# ============================================================================

@cli.command()
@click.argument('repo', required=True, shell_complete=complete_github_repo)
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def detect(ctx: CLIContext, repo: str, json_output: bool):
    """Detect project architecture (Native/Flutter/React Native/KMP)"""
    # This would need a local clone or use GitHub API
    # For now, show how it would work
    try:
        repo = validate_github_repo(repo)
        if json_output or ctx.json_output:
            print_json({"repo": repo, "detected": "unknown", "note": "Requires local project clone"})
        else:
            print_warning("Project detection requires local project path. Use 'android verify' on local project.")
    except SecurityError as e:
        print_error(e.message, e.code)
        sys.exit(1)


@cli.command()
@click.option('--json', 'json_output', is_flag=True, help='Output as JSON')
@pass_context
def version(ctx: CLIContext, json_output: bool):
    """Show version information"""
    info = {
        "version": "2.6.0",
        "name": "GitHub Actions & Codespaces Toolkit",
        "platform": "Minis AI Agent",
        "python": sys.version.split()[0],
    }
    if json_output or ctx.json_output:
        print_json(info)
    else:
        for k, v in info.items():
            click.echo(f"  {k}: {v}")


# ============================================================================
# SHELL COMPLETION
# ============================================================================

@cli.command(hidden=True)
@click.argument('shell', type=click.Choice(['bash', 'zsh', 'fish', 'powershell']))
def completion(shell: str):
    """Generate shell completion script"""
    click.echo(f"# Add to your ~/.{shell}rc:")
    click.echo(f"eval \"$(_MINIS_GH_TOOLKIT_COMPLETE={shell}_source minis-gh-toolkit)\"")


# ============================================================================
# ENTRY POINT
# ============================================================================

def main():
    """Main entry point"""
    try:
        cli(obj=CLIContext())
    except click.exceptions.Exit as e:
        sys.exit(e.exit_code)
    except Exception as e:
        logging.exception("Unhandled error")
        print_error(f"Unexpected error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()