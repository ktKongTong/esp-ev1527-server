package main

import (
	"bufio"
	"context"
	"os"
	"strings"
	"time"
)

func runCLI(ctx context.Context, gw *Gateway, log *Logger, cancel context.CancelFunc) {
	scanner := bufio.NewScanner(os.Stdin)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line != "" {
			if handleCLICommand(line, gw, log, cancel) {
				return
			}
		}
		if ctx.Err() != nil {
			return
		}
	}
	if err := scanner.Err(); err != nil {
		log.Printf("[CLI] 读取终端输入失败: %v", err)
	}
	log.Printf("[CLI] 标准输入已关闭，MQTT/HTTP 服务继续运行（CLI 请以 -i/--stdin 启动，或使用 HTTP 控制接口）")
}

// handleCLICommand returns true when the process should shut down. The console
// only exposes lifecycle controls; RF messages enter through POST
// /mock/press-button, mirroring a receiver that only reports raw codes.
func handleCLICommand(line string, gw *Gateway, log *Logger, cancel context.CancelFunc) bool {
	fields := strings.Fields(line)
	if len(fields) == 0 {
		return false
	}
	cmd := strings.ToLower(fields[0])

	switch cmd {
	case "kill":
		if len(fields) > 1 {
			log.Printf("[CLI] 用法: kill")
			return false
		}
		if err := gw.Kill(); err != nil {
			log.Printf("[CLI] kill 失败: %v", err)
		}

	case "boot":
		if len(fields) > 1 {
			log.Printf("[CLI] 用法: boot")
			return false
		}
		if err := gw.Boot(); err != nil {
			log.Printf("[CLI] boot 失败: %v", err)
		}

	case "status":
		printStatus(gw.Status(), log)

	case "help", "h", "?":
		printCLIHelp(log)

	case "quit", "exit", "q":
		log.Printf("[CLI] 正在退出模拟器...")
		cancel()
		return true

	default:
		log.Printf("[CLI] 未知指令 %q。控制台仅保留生命周期指令；RX 注入请使用 POST /mock/press-button", line)
	}
	return false
}

func printStatus(st Status, log *Logger) {
	power := "上电"
	if !st.Powered {
		power = "断电"
	}
	mqttState := "已连接"
	if !st.MQTTConnected {
		mqttState = "未连接"
	}
	log.Printf("[STATUS] 电源=%s MQTT=%s ClientID=%s Broker=%s 运行=%s",
		power, mqttState, st.ClientID, st.Broker, time.Duration(st.UptimeSeconds)*time.Second)
	log.Printf("[STATUS] LWT=%s 命令=%s 上报=%s", st.LWT, st.CommandTopic, st.RXTopic)
}

func printCLIHelp(log *Logger) {
	log.Printf("可用指令:")
	log.Printf("  kill    模拟 ESP32 意外掉电（不发送 DISCONNECT，触发 LWT=offline）")
	log.Printf("  boot    kill 之后重新上电并连接 Broker")
	log.Printf("  status  查看电源、MQTT 连接与主题信息")
	log.Printf("  help    显示本帮助")
	log.Printf("  quit    正常退出（发布 retained offline 后断开）")
	log.Printf("RX 注入: POST /mock/press-button {\"code\":1714739,\"frames\":1}")
}
