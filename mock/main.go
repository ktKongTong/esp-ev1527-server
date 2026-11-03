package main

import (
	"context"
	"errors"
	"net"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"
)

func main() {
	cfg := loadConfig()
	log := NewLogger(os.Stdout)
	printBanner(cfg, log)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	gw := NewGateway(cfg, log)
	var gatewayWG sync.WaitGroup
	gatewayWG.Add(1)
	go func() {
		defer gatewayWG.Done()
		gw.Run(ctx)
	}()

	var httpSrv *http.Server
	if cfg.HTTPAddr != "" {
		api := NewHTTPServer(cfg, gw, log)
		ln, err := net.Listen("tcp", cfg.HTTPAddr)
		if err != nil {
			log.Printf("[HTTP] 无法监听 %s: %v", cfg.HTTPAddr, err)
			cancel()
			gatewayWG.Wait()
			os.Exit(1)
		}
		httpSrv = &http.Server{
			Handler:           api.Handler(),
			ReadHeaderTimeout: 5 * time.Second,
			ReadTimeout:       10 * time.Second,
			WriteTimeout:      10 * time.Second,
			IdleTimeout:       60 * time.Second,
		}
		log.Printf("[HTTP] 控制接口已监听: http://%s/mock/press-button", displayHTTPAddr(ln.Addr().String()))
		go func() {
			if err := httpSrv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
				log.Printf("[HTTP] 服务异常退出: %v", err)
			}
		}()
	} else {
		log.Printf("[HTTP] 控制接口已禁用")
	}

	go runCLI(ctx, gw, log, cancel)

	sigCh := make(chan os.Signal, 2)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)

	select {
	case sig := <-sigCh:
		log.Printf("[MAIN] 收到信号 %s，开始退出...", sig)
	case <-ctx.Done():
		log.Printf("[MAIN] 收到退出请求，开始退出...")
	}
	cancel()

	go func() {
		<-sigCh
		log.Printf("[MAIN] 再次收到信号，强制退出")
		os.Exit(1)
	}()

	done := make(chan struct{})
	go func() {
		gatewayWG.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(8 * time.Second):
		log.Printf("[MAIN] 等待 MQTT 正常断开超时，继续退出")
	}

	if httpSrv != nil {
		shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 2*time.Second)
		_ = httpSrv.Shutdown(shutdownCtx)
		shutdownCancel()
	}
	log.Printf("[MAIN] 模拟器已退出")
}

func printBanner(cfg Config, log *Logger) {
	topics := NewTopics(cfg.TopicPrefix)
	log.Printf("============================================================")
	log.Printf(" OpenMQTTGateway 315MHz 高保真模拟器 (Go)")
	log.Printf("============================================================")
	log.Printf(" Broker      : %s", sanitizeBroker(cfg.MQTTURL))
	if cfg.Username != "" {
		log.Printf(" MQTT 认证   : user=%s", cfg.Username)
	}
	log.Printf(" ClientID    : %s", cfg.ClientID)
	log.Printf(" LWT         : %s  (QoS 1, retain=true, payload=\"offline\")", topics.LWT)
	log.Printf(" 上线广播    : %s -> \"online\" (retained)", topics.LWT)
	log.Printf(" 版本信息    : %s -> \"%s\" (retained)", topics.Version, gatewayVersion)
	log.Printf(" 命令订阅    : %s", topics.Command)
	log.Printf(" 抓码上报    : %s", topics.RX)
	if cfg.TXEcho {
		log.Printf(" TX 回显     : 开启（仅测试；发射后会模拟一次 RX 捕获）")
	} else {
		log.Printf(" TX 回显     : 关闭（符合真实网关不回显 TX 的行为）")
	}
	if cfg.HTTPAddr != "" {
		log.Printf(" HTTP 控制   : http://%s/mock/press-button", displayHTTPAddr(cfg.HTTPAddr))
	} else {
		log.Printf(" HTTP 控制   : 已禁用")
	}
	log.Printf("------------------------------------------------------------")
	log.Printf(" 终端指令    : on 1 | off 1 | rand | press <code> | burst <code> [frames] | send <code> [repeat] | kill | boot | status | help")
	log.Printf(" 预置设备    : 1号设备 开=%d (%s) / 关=%d (%s)",
		knownDevices[1].On, formatHex(knownDevices[1].On),
		knownDevices[1].Off, formatHex(knownDevices[1].Off))
	log.Printf("============================================================")
}

func displayHTTPAddr(addr string) string {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return addr
	}
	if host == "" || host == "0.0.0.0" || host == "::" {
		host = "localhost"
	}
	return net.JoinHostPort(host, port)
}
