package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func writeTestFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0600); err != nil {
		t.Fatal(err)
	}
}

func appendTestFile(t *testing.T, path, content string) {
	t.Helper()
	file, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := file.WriteString(content); err != nil {
		file.Close()
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestReadOnlySnapshot(t *testing.T) {
	home := t.TempDir()
	now := time.Now()
	sessions := filepath.Join(home, "sessions", "2026", "11", "27")
	if err := os.MkdirAll(sessions, 0700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(sessions, "rollout-test.jsonl")
	index := filepath.Join(home, "session_index.jsonl")
	at := now.Format(time.RFC3339Nano)
	meta := `{"type":"session_meta","payload":{"id":"desktop","originator":"Codex Desktop","source":"vscode"}}` + "\n"
	start := fmt.Sprintf(`{"timestamp":%q,"type":"event_msg","payload":{"type":"task_started","turn_id":"turn1"}}`, at) + "\n"
	tool := fmt.Sprintf(`{"timestamp":%q,"type":"response_item","payload":{"type":"function_call","name":"exec_command","arguments":%q}}`,
		at, strings.Repeat("a", 150_000)) + "\n"
	writeTestFile(t, path, meta+start+tool)
	writeTestFile(t, index, `{"id":"desktop","thread_name":"当前任务"}`+"\n")
	writeTestFile(t, filepath.Join(sessions, "rollout-cli.jsonl"),
		strings.Replace(meta, "Codex Desktop", "Codex CLI", 1)+start)
	writeTestFile(t, filepath.Join(sessions, "rollout-subagent.jsonl"),
		strings.Replace(meta, `"vscode"`, `{"subagent":"task"}`, 1)+start)

	source := newCodexSource(home)
	snap, err := source.snapshot(now)
	if err != nil {
		t.Fatal(err)
	}
	if snap.Live || snap.Partial || snap.CompletedCount != 0 || len(snap.Tasks) != 1 {
		t.Fatalf("unexpected running snapshot %+v", snap)
	}
	running := snap.Tasks[0]
	if running.Title != "当前任务" || running.Stage != "运行命令" || running.Requests == nil ||
		len(running.Requests) != 0 || running.Start != now.UnixMilli() {
		t.Fatalf("unexpected running task %+v", running)
	}

	complete := fmt.Sprintf(`{"timestamp":%q,"type":"event_msg","payload":{"type":"task_complete","turn_id":"turn1"}}`, at)
	appendTestFile(t, path, complete[:len(complete)-1])
	if snap, err = source.snapshot(now); err != nil || len(snap.Tasks) != 1 {
		t.Fatalf("partial record changed the running state, snapshot=%+v err=%v", snap, err)
	}
	appendTestFile(t, path, complete[len(complete)-1:]+"\n"+complete+"\n")
	snap, err = source.snapshot(now)
	if err != nil || len(snap.Tasks) != 0 || snap.CompletedCount != 1 || snap.LastCompleted[0].Title != "当前任务" {
		t.Fatalf("unexpected completed snapshot %+v, err=%v", snap, err)
	}
}

func TestFileSharingAllowsCodexToAppendAndArchive(t *testing.T) {
	path := filepath.Join(t.TempDir(), "rollout-old.jsonl")
	writeTestFile(t, path, "first\n")
	file, err := openSharedRead(path)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	appendTestFile(t, path, "second\n")
	if err := os.Rename(path, filepath.Join(filepath.Dir(path), "rollout-new.jsonl")); err != nil {
		t.Fatal("read handle prevented file rename:", err)
	}
}
