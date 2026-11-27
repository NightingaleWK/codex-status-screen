package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
	"unicode"
)

const (
	rolloutScanInterval = 5 * time.Second
	recentActivity      = 48 * time.Hour
)

type task struct {
	Thread   string     `json:"thread"`
	Turn     string     `json:"turn"`
	Title    string     `json:"title"`
	Start    int64      `json:"start"`
	Update   int64      `json:"update"`
	Stage    string     `json:"stage"`
	Requests []struct{} `json:"requests"`
}

type completedTask struct {
	Thread string `json:"thread"`
	Turn   string `json:"turn"`
	Title  string `json:"title"`
	At     int64  `json:"at"`
}

type snapshot struct {
	ObservedAt     int64           `json:"observedAt"`
	Day            string          `json:"day"`
	Live           bool            `json:"live"`
	Partial        bool            `json:"partial"`
	CompletedCount int             `json:"completedCount"`
	LastCompleted  []completedTask `json:"lastCompleted"`
	Tasks          []task          `json:"tasks"`
}

type logRecord struct {
	Type      string          `json:"type"`
	Timestamp json.RawMessage `json:"timestamp"`
	Payload   *struct {
		Type        string          `json:"type"`
		ID          string          `json:"id"`
		Originator  string          `json:"originator"`
		Source      json.RawMessage `json:"source"`
		Turn        string          `json:"turn_id"`
		RootTurn    string          `json:"root_turn_id"`
		StartedAt   json.RawMessage `json:"started_at"`
		CompletedAt json.RawMessage `json:"completed_at"`
		Name        string          `json:"name"`
		Role        string          `json:"role"`
		Item        struct {
			Type string `json:"type"`
		} `json:"item"`
	} `json:"payload"`
}

type rollout struct {
	info      os.FileInfo
	offset    int64
	ignored   bool
	thread    string
	active    *task
	completed map[string]int64
}

type codexSource struct {
	sessions   string
	titleIndex string
	nextScan   time.Time
	rollouts   map[string]*rollout
	titleInfo  os.FileInfo
	titles     map[string]string
}

func newCodexSource(home string) *codexSource {
	return &codexSource{
		sessions:   filepath.Join(home, "sessions"),
		titleIndex: filepath.Join(home, "session_index.jsonl"),
		rollouts:   make(map[string]*rollout),
	}
}

func emptySnapshot(now time.Time) snapshot {
	return snapshot{Day: now.Format(time.DateOnly), Tasks: []task{}, LastCompleted: []completedTask{}}
}

func timeMillis(value json.RawMessage, fallback int64) int64 {
	if len(value) == 0 {
		return fallback
	}
	if value[0] == '"' {
		var text string
		if json.Unmarshal(value, &text) == nil {
			if parsed, err := time.Parse(time.RFC3339Nano, text); err == nil {
				return parsed.UnixMilli()
			}
		}
	}
	if value[0] == '-' || value[0] >= '0' && value[0] <= '9' {
		var number float64
		if json.Unmarshal(value, &number) == nil {
			if number < 1e12 {
				number *= 1000
			}
			return int64(number)
		}
	}
	return fallback
}

func compactTitle(title string) string {
	words := strings.FieldsFunc(title, func(r rune) bool {
		return unicode.IsSpace(r) || unicode.In(r, unicode.C)
	})
	runes := []rune(strings.Join(words, " "))
	if len(runes) > 90 {
		runes = runes[:90]
	}
	return string(runes)
}

func (r *rollout) consume(line []byte) {
	var record logRecord
	if err := json.Unmarshal(line, &record); err != nil || record.Payload == nil {
		return
	}
	event := record.Payload
	if r.thread == "" {
		if record.Type != "session_meta" {
			return
		}
		var source string
		var origin struct{ Subagent json.RawMessage }
		_ = json.Unmarshal(event.Source, &source)
		_ = json.Unmarshal(event.Source, &origin)
		source = strings.ToLower(source)
		desktop := strings.Contains(strings.ToLower(event.Originator), "codex desktop") || source == "desktop"
		if !desktop || strings.Contains(source, "subagent") || len(origin.Subagent) > 0 {
			r.ignored = true
			return
		}
		r.thread = event.ID
		r.completed = make(map[string]int64)
		return
	}

	at := timeMillis(record.Timestamp, 0)
	if record.Type == "event_msg" {
		turn := event.Turn
		if turn == "" {
			turn = event.RootTurn
		}
		switch event.Type {
		case "task_started":
			if turn != "" {
				r.active = &task{Turn: turn, Start: timeMillis(event.StartedAt, at), Update: at,
					Stage: "开始处理", Requests: []struct{}{}}
			}
		case "task_complete", "turn_aborted":
			if turn != "" && event.Type == "task_complete" {
				r.completed[turn] = timeMillis(event.CompletedAt, at)
			}
			if r.active != nil && r.active.Turn == turn {
				r.active = nil
			}
		case "item_completed":
			if r.active != nil {
				kind := strings.ToLower(event.Item.Type)
				switch {
				case strings.Contains(kind, "reasoning"):
					r.active.Stage = "分析任务"
				case strings.Contains(kind, "agentmessage"):
					r.active.Stage = "组织回复"
				case strings.Contains(kind, "plan"):
					r.active.Stage = "更新计划"
				}
			}
		}
	}
	if r.active == nil {
		return
	}
	r.active.Update = max(r.active.Update, at)
	if record.Type != "response_item" {
		return
	}
	switch event.Type {
	case "function_call", "custom_tool_call":
		switch {
		case strings.Contains(event.Name, "apply_patch"):
			r.active.Stage = "修改文件"
		case strings.Contains(event.Name, "exec_command"), strings.Contains(event.Name, "terminal"):
			r.active.Stage = "运行命令"
		case strings.Contains(event.Name, "web"), strings.Contains(event.Name, "search"):
			r.active.Stage = "查找资料"
		default:
			r.active.Stage = "使用工具"
		}
	case "reasoning":
		r.active.Stage = "分析任务"
	case "message":
		if event.Role == "assistant" {
			r.active.Stage = "组织回复"
		}
	}
}

func unchanged(previous, current os.FileInfo) bool {
	return previous != nil && previous.Size() == current.Size() && previous.ModTime().Equal(current.ModTime())
}

func (r *rollout) read(path string, candidate os.FileInfo) error {
	if unchanged(r.info, candidate) {
		return nil
	}
	file, err := openSharedRead(path)
	if err != nil {
		return err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return err
	}
	if info.Size() < r.offset || r.info != nil && (info.Size() <= r.info.Size() || !os.SameFile(r.info, info)) {
		*r = rollout{}
	}
	// Only commit a whole line. A partially written record is read again after it grows.
	reader := bufio.NewReaderSize(io.NewSectionReader(file, r.offset, info.Size()-r.offset), 128*1024)
	for !r.ignored {
		line, err := reader.ReadBytes('\n')
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return err
		}
		r.consume(line)
		r.offset += int64(len(line))
	}
	r.info = info
	return nil
}

func (s *codexSource) scan(now time.Time) error {
	if now.Before(s.nextScan) {
		return nil
	}
	found := make(map[string]*rollout)
	err := filepath.WalkDir(s.sessions, func(path string, entry fs.DirEntry, err error) error {
		if errors.Is(err, fs.ErrNotExist) && path != s.sessions {
			return nil
		}
		if err != nil {
			return err
		}
		if entry.Type().IsRegular() && strings.HasPrefix(entry.Name(), "rollout-") && strings.HasSuffix(entry.Name(), ".jsonl") {
			current := s.rollouts[path]
			if current == nil {
				current = &rollout{}
			}
			found[path] = current
		}
		return nil
	})
	if err == nil {
		s.rollouts = found
		s.nextScan = now.Add(rolloutScanInterval)
	}
	return err
}

func (s *codexSource) refreshTitles() error {
	info, err := os.Stat(s.titleIndex)
	if err != nil {
		return err
	}
	if unchanged(s.titleInfo, info) {
		return nil
	}
	file, err := openSharedRead(s.titleIndex)
	if err != nil {
		return err
	}
	defer file.Close()
	data, err := io.ReadAll(file)
	if err != nil {
		return err
	}
	titles := make(map[string]string)
	for line := range bytes.SplitSeq(data, []byte{'\n'}) {
		var row struct {
			ID         string  `json:"id"`
			ThreadName *string `json:"thread_name"`
		}
		if json.Unmarshal(line, &row) == nil && row.ID != "" && row.ThreadName != nil {
			titles[row.ID] = compactTitle(*row.ThreadName)
		}
	}
	s.titleInfo, s.titles = info, titles
	return nil
}

func (s *codexSource) snapshot(now time.Time) (snapshot, error) {
	if err := s.scan(now); err != nil {
		return snapshot{}, err
	}
	threads := make(map[string]*rollout)
	filesRead, failures := 0, 0
	for path, r := range s.rollouts {
		info, err := os.Stat(path)
		if err == nil && info.ModTime().Before(now.Add(-recentActivity)) {
			delete(s.rollouts, path)
			continue
		}
		if err == nil {
			err = r.read(path, info)
		}
		if errors.Is(err, fs.ErrNotExist) {
			delete(s.rollouts, path)
			continue
		}
		if err != nil {
			failures++
			continue
		}
		filesRead++
		if previous := threads[r.thread]; r.thread != "" && (previous == nil || r.info.ModTime().After(previous.info.ModTime())) {
			threads[r.thread] = r
		}
	}
	if failures > 0 && filesRead == 0 {
		return snapshot{}, errors.New("无法读取 Codex 会话日志")
	}
	if err := s.refreshTitles(); err != nil && !errors.Is(err, fs.ErrNotExist) {
		failures++
	}

	status := emptySnapshot(now)
	status.ObservedAt, status.Partial = now.UnixMilli(), failures != 0
	for thread, log := range threads {
		title := s.titles[thread]
		if title == "" {
			title = "Codex 任务"
		}
		for turn, at := range log.completed {
			if time.UnixMilli(at).In(now.Location()).Format(time.DateOnly) == status.Day {
				status.LastCompleted = append(status.LastCompleted, completedTask{Thread: thread, Turn: turn, Title: title, At: at})
			}
		}
		if log.active != nil {
			active := *log.active
			active.Thread, active.Title = thread, title
			status.Tasks = append(status.Tasks, active)
		}
	}
	sort.Slice(status.LastCompleted, func(i, j int) bool { return status.LastCompleted[i].At > status.LastCompleted[j].At })
	sort.Slice(status.Tasks, func(i, j int) bool { return status.Tasks[i].Update > status.Tasks[j].Update })
	status.CompletedCount = len(status.LastCompleted)
	status.LastCompleted = status.LastCompleted[:min(12, status.CompletedCount)]
	return status, nil
}
