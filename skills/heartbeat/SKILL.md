---
name: heartbeat
description: Lightweight system health monitoring skill
version: 1.0.0
author: Agentic OS
tags: [monitoring, health, system]
---

# Heartbeat — System Health Monitor

## Description
Checks system health at regular intervals: agent status, disk usage, memory pressure, and recent audit errors. Flags anomalies and triggers alerts if thresholds are breached.

## When to Use
- Every hour as a scheduled cron job
- On-demand to check system health
- Before and after running other skills

## Process
1. Check the agents are available. claude, opencode and agy are command-line tools that only run when asked, so never look for their processes: check each command exists (`where claude`, `where opencode`, `where agy`). Hermes also runs a gateway: check http://127.0.0.1:8642/health answers
2. Check disk usage (< 90%)
3. Check memory pressure (< 80%)
4. Scan recent errors in D:/Work/Claude/agentic-os/audit/audit.log (last 200 lines)
5. Update brain/recent-decisions.md if issues found
6. Return health summary

## Output
JSON health report with pass/fail per check

## Agent Assignment
- Primary: claude
- Fallback: hermes
