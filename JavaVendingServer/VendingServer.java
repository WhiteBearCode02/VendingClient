/*
 * VendingServer.java
 * ------------------
 * 자판기 클라이언트들로부터 판매, 취소, 재고 업데이트 데이터를 수신하여 중앙에서 통합 관리하는 서버입니다.
 * 요구사항의 서버 기능 요구사항을 만족하며, 다음 기능을 구현합니다.
 *   - 각 자판기의 실시간 재고 현황 수집
 *   - 일별/월별 음료별 매출 집계
 *   - 서버 간 실시간 동기화(서버1, 서버2 데이터 일치)
 *   - 음료 이름 변경 및 재고 동기화
 * 추가 기능: Replica 기반 동기화 패킷과 서버 쿼리 응답을 통해 이중 서버 환경을 지원합니다.
 */

// [기능 설명] 다수의 자판기(클라이언트)가 동시다발적으로 접속해도 
// 스레드 풀링 및 모니터 락(Monitor Lock)을 통해 안전하게 매출을 병렬 취합하는 중앙 서버입니다.

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    private static Map<String, Map<String, Integer>> drinkDailySales = new HashMap<>();
    private static Map<String, Map<String, Integer>> drinkMonthlySales = new HashMap<>();
    private static Map<String, Integer> machineDailySales = new HashMap<>();
    private static Map<String, Integer> machineMonthlySales = new HashMap<>();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_DATE;
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final String STATE_FILE = "server_state.txt";

    private static volatile boolean isPeerConnected = false;
    private static volatile String peerHostInfo = "없음";
    private static volatile int peerPortInfo = -1;
    private static volatile String lastOutSyncTime = "없음";
    private static volatile String lastInSyncTime = "없음";

    // 공유 자원을 동기화(Synchronization)하기 위한 자바 전용 락(Lock) 객체
    private static final Object dbLock = new Object();
    private static volatile PrintWriter peerWriter = null;
    private static final Object peerLock = new Object();
    private static final Set<String> processedReplicaIds = new HashSet<>();

    // [진입점] 중앙 서버 프로세스를 시작하는 메인 함수입니다. 포트와 피어 서버 주소를 파라미터로 받습니다.
    public static void main(String[] args) {
        initServerDB();
        int port = 8080;
        String peerHost = null;
        int peerPort = -1;

        if (args.length >= 1) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
            }
        }
        if (args.length >= 3) {
            peerHost = args[1];
            try {
                peerPort = Integer.parseInt(args[2]);
            } catch (NumberFormatException ignored) {
            }
        }

        if (peerHost != null && peerPort > 0) {
            peerHostInfo = peerHost;
            peerPortInfo = peerPort;
        }

        System.out.println("==================================================");
        System.out.println("   [자판기 통합 관리 서버] 다중 접속 모드 가동   ");
        System.out.println("   포트 " + port + " 에서 실시간 데이터 수신 대기 중...  ");
        if (peerHost != null && peerPort > 0) {
            System.out.println("   동기화 대상: " + peerHost + ":" + peerPort);
        }
        System.out.println("==================================================");

        loadStateFromFile();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            if (peerHost != null && peerPort > 0) {
                startPeerConnector(peerHost, peerPort);
            }
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
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
                String packet;

                // 해당 자판기가 종료되거나 네트워크 큐에서 데이터를 쏘아 보낼 때마다 실시간으로 수신합니다.
                while ((packet = reader.readLine()) != null) {
                    processReceivedData(packet, writer);
                }
            } catch (Exception e) {
                System.out.println("[네트워크 오류] 자판기와의 통신 중 스트림이 끊어졌습니다.");
            } finally {
                System.out.println("\n[네트워크 알림] 자판기 세션이 안전하게 종료되었습니다.");
            }
        }
    }

    // [추가기능] 다른 서버 노드와 지속적으로 연결을 유지하며 상태 동기화를 수행하는 피어 커넥터 스레드입니다.
    private static void startPeerConnector(String peerHost, int peerPort) {
        Thread peerThread = new Thread(() -> {
            while (true) {
                try (Socket peerSocket = new Socket(peerHost, peerPort);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peerSocket.getInputStream()));
                     PrintWriter writer = new PrintWriter(peerSocket.getOutputStream(), true)) {
                    synchronized (peerLock) {
                        peerWriter = writer;
                        isPeerConnected = true;
                    }
                    System.out.println("[서버] 동기화 피어에 연결되었습니다: " + peerHost + ":" + peerPort);
                    sendDBSnapshotToPeer();
                    String packet;
                    while ((packet = reader.readLine()) != null) {
                        processReceivedData(packet, null);
                    }
                } catch (Exception e) {
                    System.out.println("[서버] 피어 연결이 끊어졌습니다. 5초 후 재연결 시도합니다...");
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } finally {
                    synchronized (peerLock) {
                        peerWriter = null;
                        isPeerConnected = false;
                    }
                }
            }
        });
        peerThread.setDaemon(true);
        peerThread.start();
    }

    // [추가기능] 현재 중앙 서버의 음료 재고와 가격 정보를 피어 서버로 스냅샷 전송합니다.
    private static void sendDBSnapshotToPeer() {
        StringBuilder state = new StringBuilder();
        synchronized (dbLock) {
            for (int i = 0; i < db.length; i++) {
                state.append(db[i].name).append(",").append(db[i].price).append(",").append(db[i].currentStock);
                if (i < db.length - 1)
                    state.append(";");
            }
        }
        lastOutSyncTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        replicateStateChange("SYNC_STATE|" + state.toString());
    }

    // [추가기능] 서버 간 재동기화를 위해 고유 복제 ID를 붙인 패킷을 피어로 전송합니다.
    private static void replicateStateChange(String message) {
        String replicaId = "REPL-" + UUID.randomUUID().toString();
        sendReplicaPacket("REPL_SYNC|" + replicaId + "|" + message);
    }

    private static void sendReplicaPacket(String packet) {
        synchronized (peerLock) {
            if (peerWriter != null) {
                peerWriter.println(packet);
            }
        }
    }

    // [핵심 비즈니스 로직] 패킷 파싱 및 스레드 세이프(Thread-Safe) DB 업데이트
    private static void processReceivedData(String message, PrintWriter responder) {
        processReceivedData(message, responder, false);
    }

    // [핵심 비즈니스 로직] 클라이언트 및 피어 서버로부터 수신한 모든 패킷을 파싱하여 DB를 갱신합니다.
    private static void processReceivedData(String message, PrintWriter responder, boolean isReplica) {
        String[] tokens = message.split("\\|");
        if (tokens.length < 1)
            return;

        String command = tokens[0].trim();
        if (command.equals("REPL_SYNC")) {
            String[] replTokens = message.split("\\|", 3);
            if (replTokens.length < 3)
                return;
            String replicaId = replTokens[1].trim();
            String innerMessage = replTokens[2];
            synchronized (processedReplicaIds) {
                if (processedReplicaIds.contains(replicaId)) {
                    return;
                }
                processedReplicaIds.add(replicaId);
                if (processedReplicaIds.size() > 1000) {
                    processedReplicaIds.clear();
                }
            }
            processReceivedData(innerMessage, responder, true);
            return;
        }

        String machineId = "UNKNOWN";
        String drinkName;
        int price = 0;
        int stock = -1;

        if (command.equals("SALE") || command.equals("CANCEL")) {
            if (tokens.length >= 5) {
                machineId = tokens[1].trim();
                drinkName = tokens[2].trim();
                price = Integer.parseInt(tokens[3].trim());
                stock = Integer.parseInt(tokens[4].trim());
            } else {
                drinkName = tokens[1].trim();
                price = Integer.parseInt(tokens[2].trim());
            }
        } else if (command.equals("INVENTORY") && tokens.length >= 5) {
            machineId = tokens[1].trim();
            drinkName = tokens[2].trim();
            price = Integer.parseInt(tokens[3].trim());
            stock = Integer.parseInt(tokens[4].trim());
            updateInventoryData(drinkName, price, stock);
            saveStateToFile();
            if (!isReplica) {
                replicateStateChange(message);
            }
            System.out.println("[서버] 재고 동기화 수신: " + drinkName + " | 가격: " + price + " | 재고: " + stock);
            return;
        } else if (command.equals("DRINK_UPDATE") && tokens.length >= 5) {
            machineId = tokens[1].trim();
            String oldName = tokens[2].trim();
            String newName = tokens[3].trim();
            price = Integer.parseInt(tokens[4].trim());
            synchronized (dbLock) {
                renameDrinkInDB(oldName, newName, price);
            }
            saveStateToFile();
            if (!isReplica) {
                replicateStateChange(message);
            }
            System.out.println("[서버] 음료 정보 변경 수신: " + oldName + " -> " + newName + " (" + price + "원)");
            return;
        } else if (command.equals("SYNC_STATE") && tokens.length >= 2) {
            String snapshot = tokens[1];
            applyStateSnapshot(snapshot);
            lastInSyncTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            saveStateToFile();
            return;
        } else if (command.equals("QUERY") && tokens.length >= 4) {
            String requestId = tokens[1].trim();
            String queryType = tokens[2].trim();
            String queryParam = tokens[3].trim();
            String payload = buildQueryResponse(queryType, queryParam);
            if (responder != null) {
                responder.println("RESPONSE|" + requestId + "|" + payload);
            }
            return;
        } else if (command.equals("STOCK_UPDATE") && tokens.length >= 4) {
            machineId = tokens[1].trim();
            drinkName = tokens[2].trim();
            stock = Integer.parseInt(tokens[3].trim());
            updateDrinkStockStatus(drinkName, stock);
            saveStateToFile();
            if (!isReplica) {
                replicateStateChange(message);
            }
            return;
        } else {
            return;
        }

        synchronized (dbLock) {
            for (int i = 0; i < 8; i++) {
                if (db[i].name.equals(drinkName)) {
                    if (command.equals("SALE")) {
                        db[i].currentStock = Math.max(0, stock);
                        db[i].totalSalesRevenue += price;
                        totalSystemRevenue += price;
                        updateSaleTotals(machineId, drinkName, price, true);

                        System.out.printf("\n[매출 발생] %s 판매 (+%d원) | 남은 중앙재고: %d개 | 시스템 총 매출: %d원\n",
                                drinkName, price, db[i].currentStock, totalSystemRevenue);

                        if (db[i].currentStock <= 3) {
                            System.out.println("==========================================");
                            System.out.println(" [긴급 알림] '" + drinkName + "' 재고가 " + db[i].currentStock + "개 남았습니다!!");
                            System.out.println("==========================================");
                            // [추가기능] 클라이언트에게 재고 부족 경고 패킷을 전송합니다.
                            // 서버는 콘솔뿐 아니라 실제 사용자 GUI에도 알림을 전달하여 관리자 행동을 유도합니다.
                            if (responder != null) {
                                responder.println("ALERT|LOW_STOCK|음료 '" + drinkName + "' 재고가 " + db[i].currentStock + "개 남았습니다.");
                            }
                        }
                    } else if (command.equals("CANCEL")) {
                        db[i].currentStock = Math.max(0, stock);
                        db[i].totalSalesRevenue -= price;
                        totalSystemRevenue -= price;
                        updateSaleTotals(machineId, drinkName, price, false);

                        System.out.printf("\n[환불 처리] %s 취소 (-%d원) | 복구된 중앙재고: %d개 | 시스템 총 매출: %d원\n",
                                drinkName, price, db[i].currentStock, totalSystemRevenue);
                    }
                    break;
                }
            }
        }

        if (!isReplica) {
            saveStateToFile();
            replicateStateChange(message);
        }
    }

    private static void renameDrinkInDB(String oldName, String newName, int newPrice) {
        for (ServerDB entry : db) {
            if (entry.name.equals(oldName)) {
                entry.name = newName;
                entry.price = newPrice;
                break;
            }
        }
    }

    private static void updateInventoryData(String drinkName, int price, int stock) {
        synchronized (dbLock) {
            for (ServerDB entry : db) {
                if (entry.name.equals(drinkName)) {
                    entry.price = price;
                    entry.currentStock = stock;
                    return;
                }
            }
        }
    }

    private static void updateDrinkStockStatus(String drinkName, int stock) {
        synchronized (dbLock) {
            for (ServerDB entry : db) {
                if (entry.name.equals(drinkName)) {
                    entry.currentStock = stock;
                    System.out.println("[서버] 재고 업데이트 수신: " + drinkName + " -> " + stock + "개");
                    if (stock <= 3) {
                        System.out.println("[긴급 알림] '" + drinkName + "' 재고가 " + stock + "개 이하입니다.");
                    }
                    break;
                }
            }
        }
    }

    private static void applyStateSnapshot(String snapshot) {
        synchronized (dbLock) {
            String[] items = snapshot.split(";");
            for (String item : items) {
                String[] parts = item.split(",");
                if (parts.length >= 3) {
                    String name = parts[0].trim();
                    int price = Integer.parseInt(parts[1].trim());
                    int stock = Integer.parseInt(parts[2].trim());
                    for (ServerDB entry : db) {
                        if (entry.name.equals(name)) {
                            entry.price = price;
                            entry.currentStock = stock;
                            break;
                        }
                    }
                }
            }
        }
        System.out.println("[서버] 피어로부터 재고 스냅샷을 동기화했습니다.");
    }

    // [파일 I/O] 서버 재시작 시 이전 저장 상태를 읽어와 재고 및 매출 집계 데이터를 복원합니다.
    private static void loadStateFromFile() {
        File file = new File(STATE_FILE);
        if (!file.exists())
            return;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length < 1)
                    continue;
                String type = parts[0].trim();
                switch (type) {
                    case "DB":
                        if (parts.length == 5) {
                            String name = parts[1];
                            int price = Integer.parseInt(parts[2]);
                            int stock = Integer.parseInt(parts[3]);
                            int revenue = Integer.parseInt(parts[4]);
                            for (ServerDB entry : db) {
                                if (entry.name.equals(name)) {
                                    entry.price = price;
                                    entry.currentStock = stock;
                                    entry.totalSalesRevenue = revenue;
                                    break;
                                }
                            }
                        }
                        break;
                    case "DAY":
                        if (parts.length == 4) {
                            String drink = parts[1];
                            String date = parts[2];
                            int amount = Integer.parseInt(parts[3]);
                            drinkDailySales.computeIfAbsent(drink, k -> new HashMap<>()).put(date, amount);
                        }
                        break;
                    case "MON":
                        if (parts.length == 4) {
                            String drink = parts[1];
                            String month = parts[2];
                            int amount = Integer.parseInt(parts[3]);
                            drinkMonthlySales.computeIfAbsent(drink, k -> new HashMap<>()).put(month, amount);
                        }
                        break;
                    case "MDAY":
                        if (parts.length == 4) {
                            String key = parts[1] + "|" + parts[2];
                            int amount = Integer.parseInt(parts[3]);
                            machineDailySales.put(key, amount);
                        }
                        break;
                    case "MMON":
                        if (parts.length == 4) {
                            String key = parts[1] + "|" + parts[2];
                            int amount = Integer.parseInt(parts[3]);
                            machineMonthlySales.put(key, amount);
                        }
                        break;
                    case "TOTAL":
                        if (parts.length == 2) {
                            totalSystemRevenue = Integer.parseInt(parts[1]);
                        }
                        break;
                }
            }
            System.out.println("[서버] 이전 저장 상태를 로드했습니다.");
        } catch (Exception e) {
            System.out.println("[서버] 상태 파일 로드 중 오류가 발생했습니다.");
            e.printStackTrace();
        }
    }

    // [파일 I/O] 현재 서버 상태를 한 줄씩 기록하여 재시작 후에도 일관된 데이터를 유지합니다.
    private static void saveStateToFile() {
        File file = new File(STATE_FILE);
        try (PrintWriter pw = new PrintWriter(new FileWriter(file, false))) {
            synchronized (dbLock) {
                for (ServerDB entry : db) {
                    pw.println("DB|" + entry.name + "|" + entry.price + "|" + entry.currentStock + "|" + entry.totalSalesRevenue);
                }
                for (Map.Entry<String, Map<String, Integer>> drinkEntry : drinkDailySales.entrySet()) {
                    for (Map.Entry<String, Integer> dayEntry : drinkEntry.getValue().entrySet()) {
                        pw.println("DAY|" + drinkEntry.getKey() + "|" + dayEntry.getKey() + "|" + dayEntry.getValue());
                    }
                }
                for (Map.Entry<String, Map<String, Integer>> drinkEntry : drinkMonthlySales.entrySet()) {
                    for (Map.Entry<String, Integer> monthEntry : drinkEntry.getValue().entrySet()) {
                        pw.println("MON|" + drinkEntry.getKey() + "|" + monthEntry.getKey() + "|" + monthEntry.getValue());
                    }
                }
                for (Map.Entry<String, Integer> entry : machineDailySales.entrySet()) {
                    String[] keyParts = entry.getKey().split("\\|");
                    if (keyParts.length == 2) {
                        pw.println("MDAY|" + keyParts[0] + "|" + keyParts[1] + "|" + entry.getValue());
                    }
                }
                for (Map.Entry<String, Integer> entry : machineMonthlySales.entrySet()) {
                    String[] keyParts = entry.getKey().split("\\|");
                    if (keyParts.length == 2) {
                        pw.println("MMON|" + keyParts[0] + "|" + keyParts[1] + "|" + entry.getValue());
                    }
                }
                pw.println("TOTAL|" + totalSystemRevenue);
            }
            System.out.println("[서버] 상태를 파일로 저장했습니다: " + STATE_FILE);
        } catch (Exception e) {
            System.out.println("[서버] 상태 파일 저장 중 오류가 발생했습니다.");
            e.printStackTrace();
        }
    }

    // [추가기능] 쿼리 요청에 대한 문자열 응답을 생성하여 클라이언트 혹은 관리자 화면에 전달합니다.
    private static String buildQueryResponse(String queryType, String queryParam) {
        StringBuilder sb = new StringBuilder();
        if (queryType.equals("SERVER_STATUS")) {
            sb.append("--- [서버 상태 정보] ---\n");
            sb.append("피어 연결 여부: ").append(isPeerConnected ? "연결됨" : "미연결").append("\n");
            sb.append("피어 주소: ").append(peerHostInfo).append(":").append(peerPortInfo > 0 ? peerPortInfo : 0).append("\n");
            sb.append("최종 전송 동기화 시간: ").append(lastOutSyncTime).append("\n");
            sb.append("최종 수신 동기화 시간: ").append(lastInSyncTime).append("\n");
            sb.append("총 시스템 매출: ").append(totalSystemRevenue).append("원\n");
            sb.append("--- [현재 중앙 재고] ---\n");
            synchronized (dbLock) {
                for (ServerDB entry : db) {
                    sb.append(entry.name).append(" | 가격: ").append(entry.price).append("원 | 재고: ").append(entry.currentStock).append("개 | 매출: ").append(entry.totalSalesRevenue).append("원\n");
                }
            }
            return sb.toString();
        }
        if (queryType.equals("DRINK_DAILY")) {
            sb.append("--- [서버 일별 음료별 매출] ---\n");
            sb.append(queryParam).append("\n");
            int total = 0;
            synchronized (dbLock) {
                for (ServerDB entry : db) {
                    int amount = drinkDailySales.getOrDefault(entry.name, new HashMap<>()).getOrDefault(queryParam, 0);
                    sb.append(entry.name).append(" : ").append(amount).append("원\n");
                    total += amount;
                }
            }
            sb.append("-------------------------\n");
            sb.append("총합: ").append(total).append("원");
        } else if (queryType.equals("DRINK_MONTHLY")) {
            sb.append("--- [서버 월별 음료별 매출] ---\n");
            sb.append(queryParam).append("\n");
            int total = 0;
            synchronized (dbLock) {
                for (ServerDB entry : db) {
                    int amount = drinkMonthlySales.getOrDefault(entry.name, new HashMap<>()).getOrDefault(queryParam, 0);
                    sb.append(entry.name).append(" : ").append(amount).append("원\n");
                    total += amount;
                }
            }
            sb.append("-------------------------\n");
            sb.append("총합: ").append(total).append("원");
        } else {
            sb.append("[서버] 알 수 없는 쿼리 유형: ").append(queryType);
        }
        return sb.toString();
    }

    // [통계 집계] 판매/취소 정보를 기반으로 일별, 월별 음료 및 자판기 매출 합계를 누적합니다.
    private static void updateSaleTotals(String machineId, String drinkName, int price, boolean isSale) {
        String date = LocalDate.now().format(DATE_FORMAT);
        String month = LocalDate.now().format(MONTH_FORMAT);
        int delta = isSale ? price : -price;

        drinkDailySales.computeIfAbsent(drinkName, k -> new HashMap<>());
        drinkDailySales.get(drinkName).put(date, drinkDailySales.get(drinkName).getOrDefault(date, 0) + delta);

        drinkMonthlySales.computeIfAbsent(drinkName, k -> new HashMap<>());
        drinkMonthlySales.get(drinkName).put(month, drinkMonthlySales.get(drinkName).getOrDefault(month, 0) + delta);

        String dailyMachineKey = machineId + "|" + date;
        machineDailySales.put(dailyMachineKey, machineDailySales.getOrDefault(dailyMachineKey, 0) + delta);

        String monthlyMachineKey = machineId + "|" + month;
        machineMonthlySales.put(monthlyMachineKey, machineMonthlySales.getOrDefault(monthlyMachineKey, 0) + delta);

        System.out.printf("[서버 집계] %s %s | 일별(%s): %d원 | 월별(%s): %d원 | 자판기 일별(%s): %d원 | 자판기 월별(%s): %d원\n",
                drinkName,
                (isSale ? "판매" : "취소"),
                date,
                drinkDailySales.get(drinkName).get(date),
                month,
                drinkMonthlySales.get(drinkName).get(month),
                dailyMachineKey,
                machineDailySales.get(dailyMachineKey),
                monthlyMachineKey,
                machineMonthlySales.get(monthlyMachineKey));
    }
}