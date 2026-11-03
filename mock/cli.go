package main

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"strconv"
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

// handleCLICommand returns true when the process should shut down.
func handleCLICommand(line string, gw *Gateway, log *Logger, cancel context.CancelFunc) bool {
	fields := strings.Fields(line)
	if len(fields) == 0 {
		return false
	}
	cmd := strings.ToLower(fields[0])

	switch cmd {
	case "on", "off":
		if len(fields) > 2 {
			log.Printf("[CLI] 用法: %s [设备号]，例如 %s 1", cmd, cmd)
			return false
		}
		device := 1
		if len(fields) == 2 {
			n, err := strconv.Atoi(fields[1])
			if err != nil || n <= 0 {
				log.Printf("[CLI] 无效的设备号 %q，用法: %s [设备号]", fields[1], cmd)
				return false
			}
			device = n
		}
		btn, ok := lookupDevice(device)
		if !ok {
			log.Printf("[CLI] 未配置 %d 号设备。当前预置设备 1 (on=%d / off=%d)；任意码请用 HTTP /mock/press-button 或 press <code>",
				device, knownDevices[1].On, knownDevices[1].Off)
			return false
		}
		code := btn.On
		label := fmt.Sprintf("模拟按下 %s 开", btn.Label)
		if cmd == "off" {
			code = btn.Off
			label = fmt.Sprintf("模拟按下 %s 关", btn.Label)
		}
		if _, err := gw.PressCode(code, label); err != nil {
			log.Printf("[CLI] %s 失败: %v", strings.ToUpper(cmd), err)
		}

	case "rand", "random":
		if len(fields) > 1 {
			log.Printf("[CLI] 用法: rand")
			return false
		}
		code, err := randomRFCode()
		if err != nil {
			log.Printf("[CLI] 生成随机码失败: %v", err)
			return false
		}
		if _, err := gw.PressCode(code, "模拟按下未知遥控器（配对测试）"); err != nil {
			log.Printf("[CLI] 随机码 %d (HEX: %s) 发送失败: %v", code, formatHex(code), err)
		}

	case "press":
		if len(fields) != 2 {
			log.Printf("[CLI] 用法: press <code>，例如 press 1714739 或 press 0x1A2A33")
			return false
		}
		code, err := strconv.ParseUint(fields[1], 0, 64)
		if err != nil {
			log.Printf("[CLI] 无效的 code %q", fields[1])
			return false
		}
		if _, err := gw.PressCode(code, fmt.Sprintf("模拟按下遥控器码 %s", formatHex(code))); err != nil {
			log.Printf("[CLI] press 失败: %v", err)
		}

	case "burst":
		if len(fields) < 2 || len(fields) > 3 {
			log.Printf("[CLI] 用法: burst <code> [frames]，例如 burst 1714739 3")
			return false
		}
		code, err := strconv.ParseUint(fields[1], 0, 64)
		if err != nil {
			log.Printf("[CLI] 无效的 code %q", fields[1])
			return false
		}
		frames := 3
		if len(fields) == 3 {
			frames, err = strconv.Atoi(fields[2])
			if err != nil || frames < 1 || frames > rfMaxPressFrames {
				log.Printf("[CLI] frames 必须是 1-%d 的整数", rfMaxPressFrames)
				return false
			}
		}
		if _, err := gw.PressBurst(code, fmt.Sprintf("模拟接收遥控器码 %s 的连发帧", formatHex(code)), frames, 80*time.Millisecond); err != nil {
			log.Printf("[CLI] burst 失败: %v", err)
		}

	case "send":
		if len(fields) < 2 || len(fields) > 3 {
			log.Printf("[CLI] 用法: send <code> [repeat]，例如 send 1714739 5")
			return false
		}
		code, err := strconv.ParseUint(fields[1], 0, 64)
		if err != nil {
			log.Printf("[CLI] 无效的 code %q", fields[1])
			return false
		}
		repeat := 5
		if len(fields) == 3 {
			repeat, err = strconv.Atoi(fields[2])
			if err != nil || repeat < 1 || repeat > rfMaxRepeat {
				log.Printf("[CLI] repeat 必须是 1-%d 的整数", rfMaxRepeat)
				return false
			}
		}
		if _, err := gw.TransmitCode(code, rfProtocol, rfBitLength, repeat, "CLI send"); err != nil {
			log.Printf("[CLI] send 失败: %v", err)
		}

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
		log.Printf("[CLI] 未知指令 %q，输入 help 查看可用指令", line)
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
	log.Printf("  on [n]        模拟按下 n 号设备“开”（默认 1，发码 1714739）")
	log.Printf("  off [n]       模拟按下 n 号设备“关”（默认 1，发码 1714740）")
	log.Printf("  rand          模拟按下未知新遥控器（随机 24 位码，用于配对测试）")
	log.Printf("  press <code>  发送任意 24 位遥控码，支持十进制或 0x 十六进制（单帧 RX）")
	log.Printf("  burst <code> [frames]  模拟一次物理按键产生的多帧 RX（默认 3 帧，间隔 80ms）")
	log.Printf("  send <code> [repeat]   模拟 ESP32 发射 315M 码（默认重放 5 次，不发布 MQTT）")
	log.Printf("  kill          模拟 ESP32 意外掉电（不发送 DISCONNECT，触发 LWT=offline）")
	log.Printf("  boot          模拟重新上电并连接 Broker（kill 之后使用，扩展指令）")
	log.Printf("  status        查看电源、MQTT 连接与主题信息")
	log.Printf("  help          显示本帮助")
	log.Printf("  quit          正常退出（发布 retained offline 后断开）")
}
