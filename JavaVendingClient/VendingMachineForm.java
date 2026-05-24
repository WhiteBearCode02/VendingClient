import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Map;
import java.util.Stack;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// [자료구조] 특정 가격 검색 가속을 위한 이진 탐색 트리(BST) 노드
class TreeNode {
    String drinkName;
    int price;
    TreeNode left, right;

    public TreeNode(String name, int price) {
        this.drinkName = name;
        this.price = price;
        this.left = this.right = null;
    }
}

public class VendingMachineForm extends JFrame {

    // [요구사항] 동적 할당을 활용한 화폐 입력 변수 (투입될 때마다 크기가 동적으로 변함)
    private ArrayList<Integer> dynamicInsertedMoneyList = new ArrayList<>();

    private int currentTotalMoney = 0;
    private int currentPaperMoney = 0; // 지폐 한도 5,000원 체크용

    // [요구사항] 거스름돈 기본 재고 (500원, 100원, 50원, 10원 각 10개씩)
    private int[] machineCoinStock = { 10, 10, 10, 10 };
    private final int[] COIN_VALUES = { 500, 100, 50, 10 };
    private static final int[] MINIMUM_COIN_RESERVE = { 1, 2, 2, 2 };
    private static final String ADMIN_PASSWORD_FILE = "admin_pwd.txt";
    private static final String SALES_FILE = "sales.txt";
    private static final String DAILY_SALES_FILE = "daily_sales.txt";
    private static final String MONTHLY_SALES_FILE = "monthly_sales.txt";
    private static final String DRINK_INFO_FILE = "drink_info.txt";
    private static final String COIN_STOCK_FILE = "coin_stock.txt";
    private static final String STOCK_LOG_FILE = "stock_log.txt";
    private static final String CASH_COLLECTION_FILE = "cash_collection.txt";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_DATE;
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");
    private String adminPassword;

    private JLabel balanceLabel;
    private DrinkNode head = null;
    private Stack<String> purchaseStack = new Stack<>();
    private JButton[] drinkButtons = new JButton[8];
    private String machineId = "VM-" + UUID.randomUUID().toString().substring(0, 8);
    private String serverHost = "127.0.0.1";
    private int serverPort = 8080;

    private String[] drinkNames = { "믹스커피", "고급믹스커피", "물", "캔커피", "이온음료", "고급캔커피", "탄산음료", "특화음료" };
    private int[] drinkPrices = { 200, 300, 450, 500, 550, 700, 750, 800 };
    private int[] drinkStocks = { 10, 10, 10, 10, 10, 10, 10, 10 };

    private CircularQueue networkQueue = new CircularQueue(20);
    private Socket socket;
    private PrintWriter writer;
    private boolean isNetworkActive = true;
    private Map<String, String> pendingResponses = new ConcurrentHashMap<>();
    private final Object responseLock = new Object();

    public VendingMachineForm() {
        this("127.0.0.1", 8080);
    }

    public VendingMachineForm(String serverHost, int serverPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        loadDrinkInfo();
        loadCoinStock();
        initializeInventory();
        saveDrinkInfo();
        loadAdminPassword();
        startBackgroundNetworkEngine();
        sendCurrentInventoryToServer();

        setTitle("음료 자판기");
        setSize(500, 800);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                shutdownNetworkEngine();
            }
        });

        JLabel titleLabel = new JLabel("음료 자판기", SwingConstants.CENTER);
        titleLabel.setFont(new Font("맑은 고딕", Font.BOLD, 22));
        titleLabel.setForeground(Color.BLUE);
        add(titleLabel, BorderLayout.NORTH);

        // 중앙 음료 버튼 패널
        JPanel buttonPanel = new JPanel(new GridLayout(4, 2, 10, 10));
        for (int i = 0; i < 8; i++) {
            drinkButtons[i] = new JButton();
            drinkButtons[i].setFont(new Font("맑은 고딕", Font.BOLD, 14));
            final int currentIndex = i;
            final String name = drinkNames[i];

            drinkButtons[currentIndex].addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    purchaseDrink(name, currentIndex);
                }
            });
            buttonPanel.add(drinkButtons[i]);
        }
        add(buttonPanel, BorderLayout.CENTER);

        // 하단 제어 판넬
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));

        balanceLabel = new JLabel("현재 잔액: 0원", SwingConstants.CENTER);
        balanceLabel.setFont(new Font("맑은 고딕", Font.BOLD, 18));
        balanceLabel.setOpaque(true);
        balanceLabel.setBackground(Color.LIGHT_GRAY);
        bottomPanel.add(balanceLabel, BorderLayout.NORTH);

        // [요구사항] 화폐 입력 버튼 세분화 (10, 50, 100, 500, 1000)
        JPanel moneyPanel = new JPanel(new GridLayout(1, 5, 5, 5));
        int[] moneyInputs = { 10, 50, 100, 500, 1000 };
        for (int amount : moneyInputs) {
            JButton btnMoney = new JButton(amount + "원");
            btnMoney.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    insertMoney(amount);
                }
            });
            moneyPanel.add(btnMoney);
        }
        bottomPanel.add(moneyPanel, BorderLayout.CENTER);

        // 하단 조작 버튼 모음
        JPanel actionPanel = new JPanel(new GridLayout(3, 1, 5, 5));

        JButton btnReturnMoney = new JButton("💰 반환 레버 (거스름돈 반환)");
        btnReturnMoney.setBackground(new Color(255, 228, 181));
        btnReturnMoney.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                returnChangeMoney();
            }
        });

        JButton btnUndo = new JButton("◀ 최근 구매 취소 (스택 롤백)");
        btnUndo.setBackground(new Color(255, 182, 193));
        btnUndo.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                executeUndoWithStack();
            }
        });

        JButton btnAdmin = new JButton("⚙ 시스템 관리자 모드 진입");
        btnAdmin.setBackground(Color.DARK_GRAY);
        btnAdmin.setForeground(Color.WHITE);
        btnAdmin.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openAdminMenu();
            }
        });

        actionPanel.add(btnReturnMoney);
        actionPanel.add(btnUndo);
        actionPanel.add(btnAdmin);
        bottomPanel.add(actionPanel, BorderLayout.SOUTH);

        add(bottomPanel, BorderLayout.SOUTH);

        updateUIState(); // 초기 렌더링 동기화
    }

    // [요구사항] 재고 기본값 10개 초기화 연결 리스트
    private void initializeInventory() {
        for (int i = 0; i < drinkNames.length; i++) {
            DrinkNode newNode = new DrinkNode(drinkNames[i], drinkPrices[i], drinkStocks[i]);
            if (head == null)
                head = newNode;
            else {
                DrinkNode current = head;
                while (current.getNext() != null)
                    current = current.getNext();
                current.setNext(newNode);
            }
        }
    }

    // [예외 처리 및 로직] 화폐 투입 (제한 금액 및 동적 할당 로직 적용)
    private void insertMoney(int amount) {
        boolean isPaper = (amount == 1000);

        // 예외 검증 1: 총액 7,000원 초과 방지
        if (currentTotalMoney + amount > 7000) {
            JOptionPane.showMessageDialog(this, "[경고] 총 투입 금액은 7,000원을 초과할 수 없습니다.", "한도 초과",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        // 예외 검증 2: 지폐 5,000원 초과 방지
        if (isPaper && currentPaperMoney + amount > 5000) {
            JOptionPane.showMessageDialog(this, "[경고] 지폐는 최대 5,000원까지만 투입 가능합니다.", "한도 초과",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        // [동적 할당 구현] 리스트에 투입된 화폐 객체 추가
        dynamicInsertedMoneyList.add(amount);
        currentTotalMoney += amount;
        if (isPaper)
            currentPaperMoney += amount;

        updateUIState();
    }

    // [요구사항] 그리디 알고리즘 기반 거스름돈 반환 및 메모리 해제
    private void returnChangeMoney() {
        if (currentTotalMoney == 0)
            return;

        int amountToReturn = currentTotalMoney;
        StringBuilder changeMsg = new StringBuilder("거스름돈 반환 내역:\n");
        int[] tempStock = machineCoinStock.clone(); // 트랜잭션 롤백용 임시 배열

        // O(N) 그리디 탐색
        for (int i = 0; i < COIN_VALUES.length; i++) {
            int coinNeeded = amountToReturn / COIN_VALUES[i];
            int coinToGive = Math.min(coinNeeded, tempStock[i]);

            if (coinToGive > 0) {
                amountToReturn -= (coinToGive * COIN_VALUES[i]);
                tempStock[i] -= coinToGive;
                changeMsg.append(COIN_VALUES[i] + "원: " + coinToGive + "개\n");
            }
        }

        // 예외 처리: 기기 내 잔돈이 부족한 경우
        if (amountToReturn > 0) {
            JOptionPane.showMessageDialog(this, "[거스름돈 없음] 기기 내 동전이 부족하여 반환할 수 없습니다.\n관리자에게 문의하세요.", "반환 실패",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        // 정상 반환 확정: 재고 동기화 및 메모리 해제
        machineCoinStock = tempStock;
        saveCoinStock();
        JOptionPane.showMessageDialog(this, changeMsg.toString() + "\n반환 완료되었습니다.");

        // [동적 할당 해제] 화폐가 반환되었으므로 배열 클리어 및 참조 초기화
        dynamicInsertedMoneyList.clear();
        currentTotalMoney = 0;
        currentPaperMoney = 0;
        updateUIState();
    }

    // [UI 상태 동기화] 금액에 따른 구매 가능 표시 및 품절 처리
    public void updateUIState() {
        balanceLabel.setText("현재 잔액: " + currentTotalMoney + "원");

        for (int i = 0; i < drinkNames.length; i++) {
            DrinkNode drink = findDrinkNode(drinkNames[i]);
            if (drink != null) {
                if (drink.getStock() <= 0) {
                    drinkButtons[i].setText(drink.getName() + " [품절]");
                    drinkButtons[i].setEnabled(false);
                    drinkButtons[i].setBackground(new Color(220, 220, 220));
                } else if (currentTotalMoney >= drink.getPrice()) {
                    drinkButtons[i]
                            .setText(drink.getName() + " (" + drink.getPrice() + "원) [재고:" + drink.getStock() + "]");
                    drinkButtons[i].setEnabled(true);
                    drinkButtons[i].setBackground(new Color(135, 206, 235)); // 구매 가능 강조 (하늘색)
                } else {
                    drinkButtons[i]
                            .setText(drink.getName() + " (" + drink.getPrice() + "원) [재고:" + drink.getStock() + "]");
                    drinkButtons[i].setEnabled(false);
                    drinkButtons[i].setBackground(null);
                }
            }
        }
    }

    private void purchaseDrink(String name, int index) {
        DrinkNode drink = findDrinkNode(name);
        if (drink == null || currentTotalMoney < drink.getPrice() || drink.getStock() <= 0)
            return;

        currentTotalMoney -= drink.getPrice();
        drink.setStock(drink.getStock() - 1);

        // 동적 배열에서 가장 오래된 화폐부터 결제액만큼 차감 논리 적용 (생략: 총액으로 계산)
        purchaseStack.push(drink.getName());
        sendNetworkPacket(
                "SALE|" + machineId + "|" + drink.getName() + "|" + drink.getPrice() + "|" + drink.getStock());
        saveSalesRecordToFile("SALE", drink.getName(), drink.getPrice());
        saveDrinkInfo();
        sendNetworkPacket("STOCK_UPDATE|" + machineId + "|" + drink.getName() + "|" + drink.getStock());
        if (drink.getStock() == 0) {
            logStockChange("DEPLETED", drink.getName(), -1, 0);
        }

        JOptionPane.showMessageDialog(this, drink.getName() + " 배출 완료!");
        updateUIState();
    }

    private void executeUndoWithStack() {
        if (purchaseStack.isEmpty()) {
            JOptionPane.showMessageDialog(this, "취소할 최근 구매 내역이 없습니다.");
            return;
        }

        String lastDrinkName = purchaseStack.pop();
        DrinkNode drink = findDrinkNode(lastDrinkName);

        if (drink != null) {
            currentTotalMoney += drink.getPrice();
            drink.setStock(drink.getStock() + 1);

            sendNetworkPacket(
                    "CANCEL|" + machineId + "|" + drink.getName() + "|" + drink.getPrice() + "|" + drink.getStock());
            saveSalesRecordToFile("CANCEL", drink.getName(), drink.getPrice());
            saveDrinkInfo();
            sendNetworkPacket("STOCK_UPDATE|" + machineId + "|" + drink.getName() + "|" + drink.getStock());

            JOptionPane.showMessageDialog(this, "'" + lastDrinkName + "' 구매 취소 및 금액 환불 완료!");
            updateUIState();
        }
    }

    // 관리자 모드 개방 및 폼 격리 처리
    private void openAdminMenu() {
        String pwd = JOptionPane.showInputDialog(this, "관리자 인증 패스워드를 입력하세요:");
        if (pwd != null && authenticateAdmin(pwd)) {
            AdminForm adminFrame = new AdminForm(this);
            adminFrame.setVisible(true);
            this.setVisible(false); // [요구사항] 관리자 모드 활성화 시 판매 화면 동작 중지 (독립적 동작)

            // 관리자 창이 닫힐 때 메인 창을 다시 켜기 위한 이벤트 리스너
            adminFrame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    VendingMachineForm.this.setVisible(true);
                    updateUIState();
                }
            });
        } else if (pwd != null) {
            JOptionPane.showMessageDialog(this, "인증 실패.", "보안 경고", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadAdminPassword() {
        File pwdFile = new File(ADMIN_PASSWORD_FILE);
        if (pwdFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(pwdFile))) {
                String line = reader.readLine();
                if (line != null && isValidAdminPassword(line.trim())) {
                    adminPassword = line.trim();
                    return;
                }
            } catch (Exception e) {
                System.err.println("[로드 오류] 관리자 암호 파일을 읽는 중 문제가 발생했습니다.");
                e.printStackTrace();
            }
        }

        adminPassword = "Vending!2026";
        saveAdminPassword(adminPassword);
    }

    private void saveAdminPassword(String pwd) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(ADMIN_PASSWORD_FILE, false))) {
            writer.println(pwd);
        } catch (Exception e) {
            System.err.println("[저장 오류] 관리자 암호 파일을 쓰는 중 문제가 발생했습니다.");
            e.printStackTrace();
        }
    }

    private boolean isValidAdminPassword(String pwd) {
        return pwd != null && pwd.length() >= 8 && pwd.matches(".*\\d.*") && pwd.matches(".*[^A-Za-z0-9].*");
    }

    private boolean authenticateAdmin(String pwd) {
        return pwd != null && pwd.equals(adminPassword);
    }

    public String changeAdminPassword(String currentPwd, String newPwd) {
        if (!authenticateAdmin(currentPwd)) {
            return "현재 비밀번호가 일치하지 않습니다.";
        }
        if (!isValidAdminPassword(newPwd)) {
            return "새 비밀번호는 8자리 이상이어야 하며, 숫자와 특수문자를 각각 하나 이상 포함해야 합니다.";
        }
        adminPassword = newPwd;
        saveAdminPassword(newPwd);
        return null;
    }

    // ----------------------------------------------------
    // 아래는 네트워크, 파일, 연결 리스트 탐색 유틸 로직 (기존 유지)
    // ----------------------------------------------------
    public DrinkNode findDrinkNode(String name) {
        DrinkNode current = head;
        while (current != null) {
            if (current.getName().equals(name))
                return current;
            current = current.getNext();
        }
        return null;
    }

    private void saveSalesRecordToFile(String command, String name, int price) {
        appendLineToFile(SALES_FILE, command + "|" + name + "|" + price);
        appendLineToFile(DAILY_SALES_FILE, getCurrentDate() + "|" + command + "|" + name + "|" + price);
        appendLineToFile(MONTHLY_SALES_FILE, getCurrentMonth() + "|" + command + "|" + name + "|" + price);
    }

    private void sendNetworkPacket(String packet) {
        if (packet == null || packet.trim().isEmpty() || !isNetworkActive)
            return;
        networkQueue.enqueue(packet);
    }

    private void sendCurrentInventoryToServer() {
        DrinkNode current = head;
        while (current != null) {
            sendNetworkPacket("INVENTORY|" + machineId + "|" + current.getName() + "|" + current.getPrice() + "|"
                    + current.getStock());
            current = current.getNext();
        }
    }

    private void appendLineToFile(String filename, String line) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename, true))) {
            pw.println(line);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getCurrentDate() {
        return LocalDate.now().format(DATE_FORMAT);
    }

    private String getCurrentMonth() {
        return LocalDate.now().format(MONTH_FORMAT);
    }

    private void loadDrinkInfo() {
        File file = new File(DRINK_INFO_FILE);
        if (file.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                ArrayList<String> lines = new ArrayList<>();
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                }
                if (lines.size() == drinkNames.length) {
                    for (int i = 0; i < lines.size(); i++) {
                        String[] tokens = lines.get(i).split("\\|");
                        if (tokens.length >= 3) {
                            drinkNames[i] = tokens[0].trim();
                            drinkPrices[i] = Integer.parseInt(tokens[1].trim());
                            drinkStocks[i] = Integer.parseInt(tokens[2].trim());
                        }
                    }
                    return;
                }
            } catch (Exception e) {
                System.err.println("[로드 오류] 음료 정보 파일을 읽는 중 문제가 발생했습니다.");
                e.printStackTrace();
            }
        }
        saveDrinkInfo();
    }

    private void saveDrinkInfo() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(DRINK_INFO_FILE, false))) {
            DrinkNode current = head;
            while (current != null) {
                pw.println(current.getName() + "|" + current.getPrice() + "|" + current.getStock());
                current = current.getNext();
            }
        } catch (Exception e) {
            System.err.println("[저장 오류] 음료 정보 파일을 쓰는 중 문제가 발생했습니다.");
            e.printStackTrace();
        }
    }

    private void loadCoinStock() {
        File file = new File(COIN_STOCK_FILE);
        if (file.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line = br.readLine();
                if (line != null) {
                    String[] tokens = line.split("\\|");
                    if (tokens.length == machineCoinStock.length) {
                        for (int i = 0; i < machineCoinStock.length; i++) {
                            machineCoinStock[i] = Integer.parseInt(tokens[i].trim());
                        }
                        return;
                    }
                }
            } catch (Exception e) {
                System.err.println("[로드 오류] 동전 재고 파일을 읽는 중 문제가 발생했습니다.");
                e.printStackTrace();
            }
        }
        saveCoinStock();
    }

    private void saveCoinStock() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(COIN_STOCK_FILE, false))) {
            for (int i = 0; i < machineCoinStock.length; i++) {
                pw.print(machineCoinStock[i]);
                if (i < machineCoinStock.length - 1)
                    pw.print("|");
            }
            pw.println();
        } catch (Exception e) {
            System.err.println("[저장 오류] 동전 재고 파일을 쓰는 중 문제가 발생했습니다.");
            e.printStackTrace();
        }
    }

    public String getCashStatusSummary() {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        sb.append("--- [자판기 화폐 현황] ---\n");
        for (int i = 0; i < COIN_VALUES.length; i++) {
            sb.append(COIN_VALUES[i]).append("원: ").append(machineCoinStock[i]).append("개\n");
            total += machineCoinStock[i] * COIN_VALUES[i];
        }
        sb.append("-----------------------\n");
        sb.append("총 현금 보유액: ").append(total).append("원\n");
        sb.append("최소 보유 잔여: 500원 1개, 100원 2개, 50원 2개, 10원 2개\n");
        return sb.toString();
    }

    public int collectCashFromMachine() {
        int collectedTotal = 0;
        for (int i = 0; i < machineCoinStock.length; i++) {
            int retain = MINIMUM_COIN_RESERVE[i];
            if (machineCoinStock[i] > retain) {
                int amount = (machineCoinStock[i] - retain) * COIN_VALUES[i];
                collectedTotal += amount;
                machineCoinStock[i] = retain;
            }
        }
        if (collectedTotal > 0) {
            saveCoinStock();
            appendLineToFile(CASH_COLLECTION_FILE,
                    getCurrentDate() + "|COLLECT|" + collectedTotal + "|reserve=" + MINIMUM_COIN_RESERVE[0] + ","
                            + MINIMUM_COIN_RESERVE[1] + "," + MINIMUM_COIN_RESERVE[2] + "," + MINIMUM_COIN_RESERVE[3]);
        }
        return collectedTotal;
    }

    private void logStockChange(String event, String name, int amount, int stock) {
        appendLineToFile(STOCK_LOG_FILE,
                getCurrentDate() + "|" + event + "|" + name + "|" + amount + "|" + stock);
    }

    private void startBackgroundNetworkEngine() {
        Thread networkWorker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    socket = new Socket(serverHost, serverPort);
                    writer = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));

                    Thread responseListener = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                String responseLine;
                                while (isNetworkActive && (responseLine = reader.readLine()) != null) {
                                    handleServerResponse(responseLine);
                                }
                            } catch (Exception e) {
                                System.err.println("[네트워크 오류] 서버 응답 수신 중 문제가 발생했습니다.");
                                e.printStackTrace();
                            }
                        }
                    });
                    responseListener.setDaemon(true);
                    responseListener.start();

                } catch (Exception e) {
                    System.err.println("[네트워크 오류] 서버 연결에 실패했습니다. 서버가 실행 중인지 확인하세요.");
                    e.printStackTrace();
                }

                while (isNetworkActive) {
                    if (!networkQueue.isEmpty()) {
                        String packet = networkQueue.dequeue();
                        if (packet != null && writer != null)
                            writer.println(packet);
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        networkWorker.setDaemon(true);
        networkWorker.start();
    }

    private void handleServerResponse(String responseLine) {
        if (responseLine == null || responseLine.trim().isEmpty())
            return;
        String[] tokens = responseLine.split("\\|", 3);
        if (tokens.length < 3)
            return;
        if (tokens[0].equals("RESPONSE")) {
            String requestId = tokens[1].trim();
            String payload = tokens[2];
            pendingResponses.put(requestId, payload);
            synchronized (responseLock) {
                responseLock.notifyAll();
            }
        }
    }

    public String queryServer(String queryType, String queryParam) {
        if (writer == null) {
            return "[오류] 서버에 연결되어 있지 않습니다.";
        }
        String requestId = UUID.randomUUID().toString();
        pendingResponses.put(requestId, null);
        sendNetworkPacket("QUERY|" + requestId + "|" + queryType + "|" + queryParam);
        long start = System.currentTimeMillis();
        synchronized (responseLock) {
            while (pendingResponses.get(requestId) == null && System.currentTimeMillis() - start < 5000) {
                try {
                    responseLock.wait(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        String result = pendingResponses.remove(requestId);
        return result != null ? result : "[오류] 서버 응답을 받지 못했습니다.";
    }

    private void shutdownNetworkEngine() {
        isNetworkActive = false;
        try {
            if (writer != null)
                writer.close();
            if (socket != null)
                socket.close();
        } catch (Exception e) {
            System.err.println("[종료 오류] 네트워크 엔진을 종료하는 중 문제가 발생했습니다.");
            e.printStackTrace();
        }
    }

    public void replenishStock(String name, int amount) {
        DrinkNode drink = findDrinkNode(name);
        if (drink != null) {
            drink.setStock(drink.getStock() + amount);
            saveDrinkInfo();
            logStockChange("REPLENISHED", drink.getName(), amount, drink.getStock());
            sendNetworkPacket("STOCK_UPDATE|" + machineId + "|" + drink.getName() + "|" + drink.getStock());
        }
    }

    public void updateDrinkInfo(String oldName, String newName, int newPrice) {
        DrinkNode drink = findDrinkNode(oldName);
        if (drink != null) {
            for (int i = 0; i < drinkNames.length; i++) {
                if (drinkNames[i].equals(oldName)) {
                    drinkNames[i] = newName;
                    drinkPrices[i] = newPrice;
                    try {
                        java.lang.reflect.Field nField = DrinkNode.class.getDeclaredField("name");
                        nField.setAccessible(true);
                        nField.set(drink, newName);
                        java.lang.reflect.Field pField = DrinkNode.class.getDeclaredField("price");
                        pField.setAccessible(true);
                        pField.set(drink, newPrice);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    saveDrinkInfo();
                    logStockChange("INFO_UPDATED", newName, 0, drink.getStock());
                    sendNetworkPacket("DRINK_UPDATE|" + machineId + "|" + oldName + "|" + newName + "|" + newPrice);
                    sendCurrentInventoryToServer();
                    break;
                }
            }
        }
    }

    public void buildTreeAndSearchPrice(int targetPrice) {
        TreeNode root = null;
        DrinkNode current = head;
        while (current != null) {
            root = insertTreeNode(root, current.getName(), current.getPrice());
            current = current.getNext();
        }
        StringBuilder resultSb = new StringBuilder();
        searchTreeNode(root, targetPrice, resultSb);
        if (resultSb.length() == 0)
            JOptionPane.showMessageDialog(this, targetPrice + "원에 해당하는 상품이 없습니다.");
        else
            JOptionPane.showMessageDialog(this, "--- [이진 탐색 트리 검색 결과] ---\n" + resultSb.toString());
    }

    private TreeNode insertTreeNode(TreeNode root, String name, int price) {
        if (root == null)
            return new TreeNode(name, price);
        if (price <= root.price)
            root.left = insertTreeNode(root.left, name, price);
        else
            root.right = insertTreeNode(root.right, name, price);
        return root;
    }

    private void searchTreeNode(TreeNode root, int targetPrice, StringBuilder sb) {
        if (root == null)
            return;
        if (root.price == targetPrice)
            sb.append("=> 발견: ").append(root.drinkName).append(" (").append(root.price).append("원)\n");
        if (targetPrice <= root.price)
            searchTreeNode(root.left, targetPrice, sb);
        if (targetPrice > root.price)
            searchTreeNode(root.right, targetPrice, sb);
    }

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 8080;
        if (args.length >= 1 && args[0] != null && !args[0].trim().isEmpty()) {
            host = args[0].trim();
        }
        if (args.length >= 2) {
            try {
                port = Integer.parseInt(args[1].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        final String finalHost = host;
        final int finalPort = port;
        SwingUtilities.invokeLater(() -> {
            new VendingMachineForm(finalHost, finalPort).setVisible(true);
        });
    }
}