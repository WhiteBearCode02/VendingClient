// [GitHub Commit: feat: Integrate background thread and Circular Queue socket pipeline into Swing GUI]

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Stack;

public class VendingMachineForm extends JFrame {

    private int currentInsertedMoney = 0;
    private JLabel balanceLabel;
    private DrinkNode head = null;
    private Stack<String> purchaseStack = new Stack<>();
    private JButton[] drinkButtons = new JButton[8];
    private String[] drinkNames = { "믹스커피", "고급믹스커피", "물", "캔커피", "이온음료", "고급캔커피", "탄산음료", "특화음료" };
    private int[] drinkPrices = { 200, 300, 450, 500, 550, 700, 750, 800 };

    // [핵심 자료구조] 비동기 통신을 위해 방금 구현한 원형 큐(Circular Queue)를 인스턴스화합니다.
    private CircularQueue networkQueue = new CircularQueue(20); // 최대 20개의 통신 패킷 보관용 버퍼

    // [네트워크 자원] 실시간 통신 세션을 유지하기 위한 소켓 인프라 변수
    private Socket socket;
    private PrintWriter writer;
    private boolean isNetworkActive = true; // 백그라운드 스레드 루프 제어용 플래그

    public VendingMachineForm() {
        initializeInventory();

        // [시스템 부트스트랩] 자판기가 가동되자마자 서버 접속 및 통신 전용 스레드를 비동기 가동합니다.
        startBackgroundNetworkEngine();

        setTitle("Java Swing 자판기 시뮬레이터 v1.3 (Queue & Thread 탑재)");
        setSize(450, 650);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        // 폼 닫기 이벤트 발생 시 소켓 및 스레드 자원을 안전하게 반환하는 훅(Hook) 등록
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                shutdownNetworkEngine();
            }
        });

        JLabel titleLabel = new JLabel("C언어 로직 이식 Java 자판기", SwingConstants.CENTER);
        titleLabel.setFont(new Font("맑은 고딕", Font.BOLD, 22));
        titleLabel.setForeground(Color.BLUE);
        add(titleLabel, BorderLayout.NORTH);

        JPanel buttonPanel = new JPanel(new GridLayout(4, 2, 10, 10));

        for (int i = 0; i < 8; i++) {
            final String name = drinkNames[i];
            final int price = drinkPrices[i];

            DrinkNode target = findDrinkNode(name);
            String buttonText = name + " (" + price + "원) [재고:" + (target != null ? target.getStock() : 0) + "]";

            drinkButtons[i] = new JButton(buttonText);
            drinkButtons[i].setFont(new Font("맑은 고딕", Font.PLAIN, 14));

            final int currentIndex = i;
            drinkButtons[i].addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    purchaseDrinkWithLinkedList(name, drinkButtons[currentIndex]);
                }
            });
            buttonPanel.add(drinkButtons[i]);
        }
        add(buttonPanel, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new GridLayout(3, 1, 5, 5));

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

        add(bottomPanel, BorderLayout.SOUTH);
    }

    // [기능 설명] 생산자-소비자 패턴에 의거하여, 백그라운드에서 독립 구동될 스레드를 할당하고 가동합니다.
    private void startBackgroundNetworkEngine() {
        // 자바의 Runnable 인터페이스를 익명 객체로 구현하여 스레드의 작업 명세를 정의합니다.
        Thread networkWorker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 로컬 가상 주소인 127.0.0.1의 8080 포트로 소켓 접속 파이프라인 개통
                    socket = new Socket("127.0.0.1", 8080);
                    writer = new PrintWriter(socket.getOutputStream(), true);
                    System.out.println("[네트워크 성공] 실시간 통신 서버 연결 개통 완료.");
                } catch (Exception e) {
                    System.out.println("[네트워크 알림] 서버가 offline 상태입니다. 독립 네트워크 버퍼 모드로 가동합니다.");
                }

                // [소비자 루프] 플래그가 true인 동안 무한 루프를 돌며 원형 큐의 자원을 소비(Dequeue)합니다.
                while (isNetworkActive) {
                    if (!networkQueue.isEmpty()) {
                        String packet = networkQueue.dequeue(); // 원형 큐에서 데이터 탈출
                        if (packet != null && writer != null) {
                            writer.println(packet); // 소켓 스트림을 통해 서버로 패킷 전송
                            System.out.println("[비동기 송신 성공] 큐 버퍼 파싱 전송: " + packet);
                        }
                    }
                    try {
                        // 과도한 컨텍스트 스위칭으로 인한 CPU 점유율 대폭증을 방지하기 위한 0.05초 대기 힐링 타임
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        });

        // 스레드 가동 시작 (OS 스케줄러에게 제어권 위임)
        networkWorker.start();
    }

    // [기능 설명] 프로그램 종료 시 소켓 스트림 및 스레드를 자원 누수 없이 클로징합니다.
    private void shutdownNetworkEngine() {
        isNetworkActive = false; // 소비자 루프 탈출 조건 충족
        try {
            if (writer != null)
                writer.close();
            if (socket != null)
                socket.close();
            System.out.println("[자원 해제 완료] 소켓 및 네트워크 세션이 안전하게 해제되었습니다.");
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

    private DrinkNode findDrinkNode(String name) {
        DrinkNode current = head;
        while (current != null) {
            if (current.getName().equals(name)) {
                return current;
            }
            current = current.getNext();
        }
        return null;
    }

    private void purchaseDrinkWithLinkedList(String name, JButton targetButton) {
        DrinkNode drink = findDrinkNode(name);

        if (drink == null || currentInsertedMoney < drink.getPrice() || drink.getStock() <= 0) {
            JOptionPane.showMessageDialog(this, "구매 조건을 만족하지 못했습니다.");
            return;
        }

        currentInsertedMoney -= drink.getPrice();
        drink.setStock(drink.getStock() - 1);

        purchaseStack.push(drink.getName());

        // [★ 원형 큐 생산자 연동 ★] 소켓을 직접 쏘지 않고, 원형 큐 버퍼에 Enqueue 한 뒤 O(1) 초고속 패스합니다.
        networkQueue.enqueue("SALE|" + drink.getName() + "|" + drink.getPrice());

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

            // [★ 원형 큐 생산자 연동 ★] 환불 트랜잭션 패킷 역시 원형 큐 버퍼에 적재하여 비동기로 쏘아 보냅니다.
            networkQueue.enqueue("CANCEL|" + drink.getName() + "|" + drink.getPrice());

            balanceLabel.setText("현재 잔액: " + currentInsertedMoney + "원");

            for (int i = 0; i < drinkNames.length; i++) {
                if (drinkNames[i].equals(lastDrinkName)) {
                    drinkButtons[i]
                            .setText(drink.getName() + " (" + drink.getPrice() + "원) [재고:" + drink.getStock() + "]");
                    break;
                }
            }
            JOptionPane.showMessageDialog(this, "'" + lastDrinkName + "' 구매 취소 완료!");
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new VendingMachineForm().setVisible(true);
        });
    }
}