// [기능 설명] 다수의 자판기(클라이언트)가 동시다발적으로 접속해도 
// 스레드 풀링 및 모니터 락(Monitor Lock)을 통해 안전하게 매출을 병렬 취합하는 중앙 서버입니다.

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

public class VendingServer {

    // [서버 인메모리 데이터베이스] 중앙에서 8개 음료의 통합 재고와 매출을 추적하는 구조체 클래스
    static class ServerDB {
        String name;
        int price;
        int currentStock;
        int totalSalesRevenue;

        public ServerDB(String name, int price, int initialStock) {
            this.name = name;
            this.price = price;
            this.currentStock = initialStock;
            this.totalSalesRevenue = 0;
        }
    }

    // 전역 공유 자원 (Global Shared Resources)
    private static ServerDB[] db = new ServerDB[8];
    private static int totalSystemRevenue = 0;

    // 공유 자원을 동기화(Synchronization)하기 위한 자바 전용 락(Lock) 객체
    private static final Object dbLock = new Object();

    public static void main(String[] args) {
        initServerDB();

        System.out.println("==================================================");
        System.out.println("   [자판기 통합 관리 서버] 다중 접속 모드 가동   ");
        System.out.println("   포트 8080 에서 실시간 데이터 수신 대기 중...  ");
        System.out.println("==================================================");

        try (ServerSocket serverSocket = new ServerSocket(8080)) {
            while (true) {
                // 1. 자판기가 접속할 때까지 스레드를 블로킹(대기)합니다.
                Socket clientSocket = serverSocket.accept();
                System.out.println("\n[네트워크 알림] 새로운 자판기 노드가 시스템에 접속했습니다! (IP: " + clientSocket.getInetAddress() + ")");

                // 2. [멀티스레드 분기] 접속한 자판기 전담 워커 스레드를 할당하여 백그라운드로 넘깁니다.
                // 메인 스레드는 곧바로 다음 자판기의 접속을 기다리러 루프 처음으로 돌아갑니다.
                ClientHandler handler = new ClientHandler(clientSocket);
                handler.start();
            }
        } catch (Exception e) {
            System.out.println("[서버 치명적 오류] 포트 바인딩 또는 소켓 개통에 실패했습니다.");
            e.printStackTrace();
        }
    }

    // [기능 설명] 중앙 서버용 재고 데이터베이스 초기 세팅
    private static void initServerDB() {
        String[] names = { "믹스커피", "고급믹스커피", "물", "캔커피", "이온음료", "고급캔커피", "탄산음료", "특화음료" };
        int[] prices = { 200, 300, 450, 500, 550, 700, 750, 800 };
        for (int i = 0; i < 8; i++) {
            db[i] = new ServerDB(names[i], prices[i], 10); // 초기 재고 10개로 넉넉하게 설정
        }
    }

    // [기능 설명] 개별 자판기(Client)와의 통신 세션을 전담하는 스레드 클래스입니다.
    static class ClientHandler extends Thread {
        private Socket socket;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                String packet;

                // 해당 자판기가 종료되거나 네트워크 큐에서 데이터를 쏘아 보낼 때마다 실시간으로 수신합니다.
                while ((packet = reader.readLine()) != null) {
                    processReceivedData(packet);
                }
            } catch (Exception e) {
                System.out.println("[네트워크 오류] 자판기와의 통신 중 스트림이 끊어졌습니다.");
            } finally {
                System.out.println("\n[네트워크 알림] 자판기 세션이 안전하게 종료되었습니다.");
            }
        }
    }

    // [핵심 비즈니스 로직] 패킷 파싱 및 스레드 세이프(Thread-Safe) DB 업데이트
    private static void processReceivedData(String message) {
        String[] tokens = message.split("\\|");
        if (tokens.length < 3)
            return;

        String command = tokens[0];
        String drinkName = tokens[1];
        int price = Integer.parseInt(tokens[2]);

        // [★ 경쟁 상태(Race Condition) 방지 ★]
        // 여러 자판기가 동시에 이 코드를 실행해도, synchronized 블록 안에 들어갈 수 있는 스레드는 단 하나뿐입니다. (C언어의
        // Mutex 완벽 대체)
        synchronized (dbLock) {
            for (int i = 0; i < 8; i++) {
                if (db[i].name.equals(drinkName)) {
                    if (command.equals("SALE")) {
                        db[i].currentStock--;
                        db[i].totalSalesRevenue += price;
                        totalSystemRevenue += price;

                        System.out.printf("\n[매출 발생] %s 판매 (+%d원) | 남은 중앙재고: %d개 | 시스템 총 매출: %d원\n",
                                drinkName, price, db[i].currentStock, totalSystemRevenue);

                        // 요구사항: 재고 부족 시 긴급 알림 트리거
                        if (db[i].currentStock <= 3) {
                            System.out.println("==========================================");
                            System.out.println(" [긴급 알림] '" + drinkName + "' 재고가 " + db[i].currentStock + "개 남았습니다!!");
                            System.out.println("==========================================");
                        }
                    } else if (command.equals("CANCEL")) {
                        db[i].currentStock++;
                        db[i].totalSalesRevenue -= price;
                        totalSystemRevenue -= price;

                        System.out.printf("\n[환불 처리] %s 취소 (-%d원) | 복구된 중앙재고: %d개 | 시스템 총 매출: %d원\n",
                                drinkName, price, db[i].currentStock, totalSystemRevenue);
                    }
                    break;
                }
            }
        } // 락(Lock) 해제: 대기하던 다른 자판기의 데이터가 처리됩니다.
    }
}