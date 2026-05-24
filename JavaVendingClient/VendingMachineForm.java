import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Stack;

// [자료구조] 특정 가격 검색 가속을 위한 이진 탐색 트리(BST) 노드 정의 클래스입니다.
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

    private int currentInsertedMoney = 0;
    private JLabel balanceLabel;
    private DrinkNode head = null;
    private Stack<String> purchaseStack = new Stack<>();
    private JButton[] drinkButtons = new JButton[8];
    private String[] drinkNames = { "믹스커피", "고급믹스커피", "물", "캔커피", "이온음료", "고급캔커피", "탄산음료", "특화음료" };
    private int[] drinkPrices = { 200, 300, 450, 500, 550, 700, 750, 800 };

    private CircularQueue networkQueue = new CircularQueue(20);
    private Socket socket;
    private PrintWriter writer;
    private boolean isNetworkActive = true;

    public VendingMachineForm() {
        // 백엔드 인벤토리 연결 리스트 초기화 가동
        initializeInventory();
        startBackgroundNetworkEngine();

        setTitle("Java Swing 자판기 시뮬레이터 v1.4 (전체 모듈 통합본)");
        setSize(450, 700); // 관리자 버튼 추가로 가로세로 비율 최적화
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                shutdownNetworkEngine();
            }
        });

        JLabel titleLabel = new JLabel("음료 판매 자판기", SwingConstants.CENTER);
        titleLabel.setFont(new Font("맑은 고딕", Font.BOLD, 22));
        titleLabel.setForeground(Color.BLUE);
        add(titleLabel, BorderLayout.NORTH);

        JPanel buttonPanel = new JPanel(new GridLayout(4, 2, 10, 10));

        for (int i = 0; i < 8; i++) {
            refreshButtonText(i); // 버튼 텍스트 초기화 및 생성

            // 이벤트 리스너 내부로 전달하기 위한 불변(final) 로컬 변수 할당
            final int currentIndex = i;
            final String name = drinkNames[i];

            // [핵심 해결] 마우스 클릭 이벤트를 감지하여 구매 함수와 연결(Binding)
            drinkButtons[currentIndex].addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    purchaseDrinkWithLinkedList(name, drinkButtons[currentIndex]);
                }
            });

            buttonPanel.add(drinkButtons[i]);
        }
        add(buttonPanel, BorderLayout.CENTER);

        // 하단 복합 제어 판넬 (잔액, 투입, 취소, 관리자 진입 버튼 집약)
        JPanel bottomPanel = new JPanel(new GridLayout(4, 1, 5, 5));

        balanceLabel = new JLabel("현재 잔액: 0원", SwingConstants.CENTER);
        balanceLabel.setFont(new Font("맑은 고딕", Font.BOLD, 18));
        balanceLabel.setOpaque(true);
        balanceLabel.setBackground(Color.LIGHT_GRAY);
        bottomPanel.add(balanceLabel);

        JButton btnInsert1000 = new JButton("1,000원 투입하기");
        btnInsert1000.setFont(new Font("맑은 고딕", Font.BOLD, 14));
        btnInsert1000.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (currentInsertedMoney + 1000 <= 7000) {
                    currentInsertedMoney += 1000;
                    balanceLabel.setText("현재 잔액: " + currentInsertedMoney + "원");
                } else {
                    JOptionPane.showMessageDialog(null, "투입 한도(7,000원)를 초과했습니다.", "한도 초과", JOptionPane.WARNING_MESSAGE);
                }
            }
        });
        bottomPanel.add(btnInsert1000);

        JButton btnUndo = new JButton("◀ 최근 구매 취소 (환불)");
        btnUndo.setFont(new Font("맑은 고딕", Font.BOLD, 14));
        btnUndo.setBackground(new Color(255, 182, 193));
        btnUndo.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                executeUndoWithStack();
            }
        });
        bottomPanel.add(btnUndo);

        // [★ 새 기능 추가 ★] 9. 시스템 관리자 모드 진입 컴포넌트 버튼 추가
        JButton btnAdmin = new JButton("⚙ 시스템 관리자 모드 진입 (비밀번호)");
        btnAdmin.setFont(new Font("맑은 고딕", Font.BOLD, 14));
        btnAdmin.setBackground(Color.DARK_GRAY);
        btnAdmin.setForeground(Color.WHITE);
        btnAdmin.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String pwd = JOptionPane.showInputDialog("관리자 인증 패스워드를 입력하세요:");
                if (pwd != null && pwd.equals("Vending!2026")) {
                    // 의존성 주입 기법을 통해 자기 자신(this)의 인스턴스 주소를 관리자 폼에 넘기며 새 창을 활성화합니다.
                    AdminForm adminFrame = new AdminForm(VendingMachineForm.this);
                    adminFrame.setVisible(true);
                } else if (pwd != null) {
                    JOptionPane.showMessageDialog(null, "인증 패스워드가 불일치합니다.", "보안 경고", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        bottomPanel.add(btnAdmin);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    // [기능 설명] 연결 리스트의 상태가 갱신되었을 때 GUI 버튼의 텍스트 레이블을 리렌더링하는 헬퍼 함수입니다.
    public void refreshButtonText(int index) {
        String name = drinkNames[index];
        int price = drinkPrices[index];
        DrinkNode target = findDrinkNode(name);
        String buttonText = name + " (" + price + "원) [재고:" + (target != null ? target.getStock() : 0) + "]";

        if (drinkButtons[index] == null) {
            drinkButtons[index] = new JButton(buttonText);
            drinkButtons[index].setFont(new Font("맑은 고딕", Font.PLAIN, 14));
        } else {
            drinkButtons[index].setText(buttonText);
        }
    }

    private void startBackgroundNetworkEngine() {
        Thread networkWorker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    socket = new Socket("127.0.0.1", 8080);
                    writer = new PrintWriter(socket.getOutputStream(), true);
                } catch (Exception e) {
                    System.out.println("[네트워크 알림] 서버 오프라인 가동 모드.");
                }

                while (isNetworkActive) {
                    if (!networkQueue.isEmpty()) {
                        String packet = networkQueue.dequeue();
                        if (packet != null && writer != null) {
                            writer.println(packet);
                        }
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        });
        networkWorker.start();
    }

    private void shutdownNetworkEngine() {
        isNetworkActive = false;
        try {
            if (writer != null)
                writer.close();
            if (socket != null)
                socket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initializeInventory() {
        for (int i = 0; i < drinkNames.length; i++) {
            DrinkNode newNode = new DrinkNode(drinkNames[i], drinkPrices[i], 5);
            if (head == null) {
                head = newNode;
            } else {
                DrinkNode current = head;
                while (current.getNext() != null) {
                    current = current.getNext();
                }
                current.setNext(newNode);
            }
        }
    }

    public DrinkNode findDrinkNode(String name) {
        DrinkNode current = head;
        while (current != null) {
            if (current.getName().equals(name)) {
                return current;
            }
            current = current.getNext();
        }
        return null;
    }

    // [기능 설명] 영속성 트랜잭션을 처리하기 위해 로컬 디스크의 sales.txt 파일 끝에 데이터를 어펜드(Append) 모드로 기록합니다.
    private void saveSalesRecordToFile(String command, String name, int price) {
        try (PrintWriter pw = new PrintWriter(new FileWriter("sales.txt", true))) {
            pw.println(command + "|" + name + "|" + price);
        } catch (Exception ex) {
            System.out.println("[파일 오류] 매출 파일 기록 실패");
        }
    }

    private void purchaseDrinkWithLinkedList(String name, JButton targetButton) {
        DrinkNode drink = findDrinkNode(name);

        if (drink == null || currentInsertedMoney < drink.getPrice() || drink.getStock() <= 0) {
            JOptionPane.showMessageDialog(this, "구매 조건을 충족하지 못했습니다.");
            return;
        }

        currentInsertedMoney -= drink.getPrice();
        drink.setStock(drink.getStock() - 1);

        purchaseStack.push(drink.getName());
        networkQueue.enqueue("SALE|" + drink.getName() + "|" + drink.getPrice());

        // [★ 로컬 파일 I/O 연동 ★] 영속성 파일 데이터 저장소에 동기화 쓰기 수행
        saveSalesRecordToFile("SALE", drink.getName(), drink.getPrice());

        balanceLabel.setText("현재 잔액: " + currentInsertedMoney + "원");
        targetButton.setText(drink.getName() + " (" + drink.getPrice() + "원) [재고:" + drink.getStock() + "]");

        JOptionPane.showMessageDialog(this, drink.getName() + " 구매 완료!");
    }

    private void executeUndoWithStack() {
        if (purchaseStack.isEmpty()) {
            JOptionPane.showMessageDialog(this, "취소할 최근 구매 내역이 존재하지 않습니다.");
            return;
        }

        String lastDrinkName = purchaseStack.pop();
        DrinkNode drink = findDrinkNode(lastDrinkName);

        if (drink != null) {
            currentInsertedMoney += drink.getPrice();
            drink.setStock(drink.getStock() + 1);

            networkQueue.enqueue("CANCEL|" + drink.getName() + "|" + drink.getPrice());

            // [★ 로컬 파일 I/O 연동 ★] 취소 내역 역시 파일 데이터베이스에 기록하여 정렬 싱크 보장
            saveSalesRecordToFile("CANCEL", drink.getName(), drink.getPrice());

            balanceLabel.setText("현재 잔액: " + currentInsertedMoney + "원");

            for (int i = 0; i < drinkNames.length; i++) {
                if (drinkNames[i].equals(lastDrinkName)) {
                    refreshButtonText(i);
                    break;
                }
            }
            JOptionPane.showMessageDialog(this, "'" + lastDrinkName + "' 구매 취소 완료!");
        }
    }

    // ==========================================================
    // [관리자 모드 브릿지 연동을 위한 전용 핵심 함수 레이어]
    // ==========================================================
    public void replenishStock(String name, int amount) {
        DrinkNode drink = findDrinkNode(name);
        if (drink != null) {
            drink.setStock(drink.getStock() + amount);
            for (int i = 0; i < drinkNames.length; i++) {
                if (drinkNames[i].equals(name)) {
                    refreshButtonText(i);
                    break;
                }
            }
        }
    }

    public void updateDrinkInfo(String oldName, String newName, int newPrice) {
        DrinkNode drink = findDrinkNode(oldName);
        if (drink != null) {
            // 연결 리스트 데이터 동적 변이
            drink.setStock(drink.getStock()); // 기존 재고 승계
            for (int i = 0; i < drinkNames.length; i++) {
                if (drinkNames[i].equals(oldName)) {
                    drinkNames[i] = newName;
                    drinkPrices[i] = newPrice;
                    // 연결리스트 내부 노드 속성 직접 수정
                    try {
                        java.lang.reflect.Field nameField = DrinkNode.class.getDeclaredField("name");
                        nameField.setAccessible(true);
                        nameField.set(drink, newName);
                        java.lang.reflect.Field priceField = DrinkNode.class.getDeclaredField("price");
                        priceField.setAccessible(true);
                        priceField.set(drink, newPrice);
                    } catch (Exception e) {
                    }
                    refreshButtonText(i);
                    break;
                }
            }
        }
    }

    // [이진 탐색 트리 내부 빌드 및 O(log n) 탐색 실행 브릿지]
    public void buildTreeAndSearchPrice(int targetPrice) {
        TreeNode root = null;
        DrinkNode current = head;
        while (current != null) {
            root = insertTreeNode(root, current.getName(), current.getPrice());
            current = current.getNext();
        }
        StringBuilder resultSb = new StringBuilder();
        searchTreeNode(root, targetPrice, resultSb);
        if (resultSb.length() == 0) {
            JOptionPane.showMessageDialog(this, targetPrice + "원에 해당하는 상품이 트리에 존재하지 않습니다.");
        } else {
            JOptionPane.showMessageDialog(this, "--- [이진 탐색 트리 검색 결과] ---\n" + resultSb.toString());
        }
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
            sb.append("=> 트리 노드 발견: ").append(root.drinkName).append(" (").append(root.price).append("원)\n");
        if (targetPrice <= root.price)
            searchTreeNode(root.left, targetPrice, sb);
        if (targetPrice > root.price)
            searchTreeNode(root.right, targetPrice, sb);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new VendingMachineForm().setVisible(true);
        });
    }
}