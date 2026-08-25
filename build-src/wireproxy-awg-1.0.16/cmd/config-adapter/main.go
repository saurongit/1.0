package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	wireproxyawg "github.com/artem-russkikh/wireproxy-awg"
)

const maxConfigBytes = 1024 * 1024

func main() {
	inputPath := flag.String("in", "", "source AWG/WG config")
	outputPath := flag.String("out", "", "prepared config path")
	flag.Parse()

	if *inputPath == "" || *outputPath == "" {
		fmt.Fprintln(os.Stderr, "both -in and -out are required")
		os.Exit(2)
	}
	if err := adapt(*inputPath, *outputPath); err != nil {
		fmt.Fprintln(os.Stderr, "config adapter:", err)
		os.Exit(1)
	}
	fmt.Println(*outputPath)
}

func adapt(inputPath, outputPath string) error {
	data, err := os.ReadFile(inputPath)
	if err != nil {
		return err
	}
	if len(data) == 0 {
		return fmt.Errorf("source config is empty")
	}
	if len(data) > maxConfigBytes {
		return fmt.Errorf("source config exceeds 1 MiB")
	}

	prepared := withLocalSocks(string(data))
	outputDir := filepath.Dir(outputPath)
	temp, err := os.CreateTemp(outputDir, ".awg-config-*.tmp")
	if err != nil {
		return err
	}
	tempPath := temp.Name()
	defer os.Remove(tempPath)

	if err := temp.Chmod(0o600); err != nil {
		temp.Close()
		return err
	}
	if _, err := temp.WriteString(prepared); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	if _, err := wireproxyawg.ParseConfig(tempPath); err != nil {
		return fmt.Errorf("wireproxy rejected prepared config: %w", err)
	}
	if err := os.Rename(tempPath, outputPath); err != nil {
		return err
	}
	return os.Chmod(outputPath, 0o600)
}

func withLocalSocks(text string) string {
	text = strings.ReplaceAll(text, "\r\n", "\n")
	text = strings.ReplaceAll(text, "\r", "\n")
	lines := strings.Split(text, "\n")
	result := make([]string, 0, len(lines)+3)
	skippingSocks := false
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "[") && strings.HasSuffix(trimmed, "]") {
			skippingSocks = strings.EqualFold(trimmed, "[Socks5]")
			if skippingSocks {
				continue
			}
		}
		if !skippingSocks {
			result = append(result, line)
		}
	}
	return strings.Join(result, "\n") + "\n\n[Socks5]\nBindAddress = 127.0.0.1:1080\n"
}
