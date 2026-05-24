// [GitHub Commit: feat: Implement Undo system using java.util.Stack for purchase history rollback]

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Stack; // 자바 표준 스택 라이브러리 포함

public class VendingMachineForm extends JFrame {

    private int currentInsertedMoney = 0;
    private JLabel balanceLabel;
    private DrinkNode head = null;

    // [핵심 자료구조] 최근 구매 내역을 LIFO(Last-In, First-Out) 구조로 기억할 스택 선언
    // 자바의 제네릭(<>) 기능을 사용하여 오직 문자열(음료 이름)만 담도록 타입을 강제합니다.
    private Stack<String> purchaseStack = new Stack<>();

    // 각 음료 버튼들의 참조를 보관할 배열 (환불 시 버튼 텍스트를 실시간으로 갱신하기 위함)
    private JButton[] drinkButtons = new JButton[8];
    private String[] drinkNames = { "믹스커피", "고급믹스커피", "물", "캔커피", "이온음료", "고급캔커피", "탄산음료", "특화음료" };
    private int[] drinkPrices = { 200, 300, 450, 500, 550, 700, 750, 800 };

    public VendingMachineForm() {
        initializeInventory();

        setTitle("Java Swing 자판기 시뮬레이터 v1.2 (Stack 탑재)");
        setSize(450, 650); // 취소 버튼 추가로 인해 세로 크기 확장
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

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

        // 하단 UI 레이아웃 설정 (잔액 표시, 금액 투입 버튼, 구매 취소 버튼 복합 구성)
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

        // [★ 새 기능 추가 ★] 스택 자료구조를 호출하여 환불을 수행하는 GUI 버튼 생성
        JButton btnUndo = new JButton("◀ 최근 구매 취소 (환불)");
        btnUndo.setFont(new Font("맑은 고딕", Font.BOLD, 14));
        btnUndo.setBackground(new Color(255, 182, 193)); // 연분홍색으로 구분감 부여
        btnUndo.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // 스택 기반 롤백 비즈니스 로직 함수 호출
                executeUndoWithStack();
            }
        });
        bottomPanel.add(btnUndo);

        add(bottomPanel, BorderLayout.SOUTH);
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

        if (drink == null) {
            JOptionPane.showMessageDialog(this, "존재하지 않는 상품입니다.", "오류", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (currentInsertedMoney < drink.getPrice()) {
            JOptionPane.showMessageDialog(this, "잔액이 부족합니다.", "잔액 부족", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (drink.getStock() <= 0) {
            JOptionPane.showMessageDialog(this, drink.getName() + " 제품이 품절되었습니다.", "품절", JOptionPane.WARNING_MESSAGE);
            return;
        }

        currentInsertedMoney -= drink.getPrice();
        drink.setStock(drink.getStock() - 1);

        // [스택 연동] 트랜잭션 성공 시, 해당 음료의 이름을 스택의 최상단(Top)에 푸시(Push)합니다.
        purchaseStack.push(drink.getName());

        balanceLabel.setText("현재 잔액: " + currentInsertedMoney + "원");
        targetButton.setText(drink.getName() + " (" + drink.getPrice() + "원) [재고:" + drink.getStock() + "]");

        JOptionPane.showMessageDialog(this, drink.getName() + " 구매 완료!\n남은 재고: " + drink.getStock() + "개");
    }

    // [기능 설명] 사용자가 '구매 취소' 버튼을 누르면 스택에서 데이터를 팝(Pop)하여 상태를 복구합니다.
    private void executeUndoWithStack() {
        // 1. Stack Underflow 방지: 스택이 비어있는지 사전에 확인합니다.
        if (purchaseStack.isEmpty()) {
            JOptionPane.showMessageDialog(this, "취소할 최근 구매 내역이 존재하지 않습니다.", "안내", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // 2. LIFO 원칙에 따라 가장 마지막에 삽입된 음료 이름을 스택에서 추출(Pop)하며 제거합니다.
        String lastDrinkName = purchaseStack.pop();
        DrinkNode drink = findDrinkNode(lastDrinkName);

        if (drink != null) {
            // 3. 상태 변이 복구(Rollback): 차감되었던 금액을 돌려주고 연결 리스트 재고를 1 복구합니다.
            currentInsertedMoney += drink.getPrice();
            drink.setStock(drink.getStock() + 1);

            // 4. 화면 UI 컴포넌트 실시간 동기화
            balanceLabel.setText("현재 잔액: " + currentInsertedMoney + "원");

            // 해당 음료 버튼의 인덱스를 찾아 버튼 글자 업데이트
            for (int i = 0; i < drinkNames.length; i++) {
                if (drinkNames[i].equals(lastDrinkName)) {
                    drinkButtons[i]
                            .setText(drink.getName() + " (" + drink.getPrice() + "원) [재고:" + drink.getStock() + "]");
                    break;
                }
            }

            JOptionPane.showMessageDialog(this, "'" + lastDrinkName + "' 구매가 성공적으로 취소되었습니다.\n금액 및 재고가 롤백되었습니다.");
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new VendingMachineForm().setVisible(true);
        });
    }
}