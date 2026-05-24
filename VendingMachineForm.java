// [GitHub Commit: feat: Rebase project directory to Documents and setup GUI blueprint]

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

// [기능 설명] Java의 표준 GUI 라이브러리인 Swing의 JFrame을 상속받아 자판기 창 시스템을 정의합니다.
public class VendingMachineForm extends JFrame {
    
    // [기능 설명] 자판기 내부 상태를 관리하는 인메모리(In-memory) 변수들입니다.
    private int currentInsertedMoney = 0; // 동적 할당 없이 자바 힙 메모리에 상주하는 잔액 변수
    private JLabel balanceLabel;           // 화면에 실시간 잔액 텍스트를 표현할 UI 컴포넌트

    // [기능 설명] GUI 창의 모양과 버튼 배치를 담당하는 생성자(Constructor)입니다.
    public VendingMachineForm() {
        // 1. 메인 윈도우 창의 기본 프레임 스펙 설정
        setTitle("Java Swing 자판기 시뮬레이터 v1.0");
        setSize(450, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); // 창을 닫으면 프로세스도 완전히 종료하도록 명시
        setLayout(new BorderLayout(10, 10)); // 동서남북 구획 레이아웃 설정

        // 2. 상단 상단 배너 레이블 설정 (C언어의 메인 타이틀 printf 대체)
        JLabel titleLabel = new JLabel("C언어 로직 이식 Java 자판기", SwingConstants.CENTER);
        titleLabel.setFont(new Font("맑은 고딕", Font.BOLD, 22));
        titleLabel.setForeground(Color.BLUE);
        add(titleLabel, BorderLayout.NORTH);

        // 3. 중앙 영역: 음료 구매 버튼들을 격자 구조(Grid)로 정렬하여 배치할 패널
        JPanel buttonPanel = new JPanel(new GridLayout(4, 2, 10, 10)); // 4행 2열 격자 구조
        
        // 요구사항에 명시되었던 8개 음료 이름을 배열로 루프 바인딩
        String[] drinkNames = {
            "믹스커피 (200원)", "고급믹스커피 (300원)", "물 (450원)", "캔커피 (500원)",
            "이온음료 (550원)", "고급캔커피 (700원)", "탄산음료 (750원)", "특화음료 (800원)"
        };
        int[] drinkPrices = {200, 300, 450, 500, 550, 700, 750, 800};

        for (int i = 0; i < 8; i++) {
            final String name = drinkNames[i].split(" ")[0]; // 이름만 파싱
            final int price = drinkPrices[i];

            JButton btn = new JButton(drinkNames[i]);
            btn.setFont(new Font("맑은 고딕", Font.PLAIN, 14));

            // [핵심 인터페이스] 사용자가 마우스로 버튼을 클릭했을 때 작동할 이벤트 리스너(Listener) 바인딩
            btn.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    // 추후 다음 단계에서 구현할 연결 리스트 재고 차감 비즈니스 로직 함수 호출부 연결
                    purchaseDrinkLogic(name, price);
                }
            });
            buttonPanel.add(btn);
        }
        add(buttonPanel, BorderLayout.CENTER);

        // 4. 하단 영역: 금액 투입 버튼 및 실시간 잔액 시각화 레이어 구성
        JPanel bottomPanel = new JPanel(new GridLayout(2, 1, 5, 5));
        
        balanceLabel = new JLabel("현재 잔액: 0원", SwingConstants.CENTER);
        balanceLabel.setFont(new Font("맑은 고딕", Font.BOLD, 18));
        balanceLabel.setOpaque(true);
        balanceLabel.setBackground(Color.LIGHT_GRAY);
        bottomPanel.add(balanceLabel);

        // 금액 투입 이벤트 유도용 버튼 생성
        JButton btnInsert1000 = new JButton("1,000원 투입하기");
        btnInsert1000.setFont(new Font("맑은 고딕", Font.BOLD, 14));
        btnInsert1000.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // 기존 insertMoney 로직 이식
                if (currentInsertedMoney + 1000 <= 7000) {
                    currentInsertedMoney += 1000;
                    balanceLabel.setText("현재 잔액: " + currentInsertedMoney + "원");
                } else {
                    // Java 내장 그래픽 모달창인 경고 팝업 생성
                    JOptionPane.showMessageDialog(null, "투입 한도(7,000원)를 초과했습니다.", "한도 초과", JOptionPane.WARNING_MESSAGE);
                }
            }
        });
        bottomPanel.add(btnInsert1000);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    // [기능 설명] C언어 vending_machine.c의 purchaseDrink 알고리즘이 들어갈 임시 스텁(Stub) 함수입니다.
    private void purchaseDrinkLogic(String name, int price) {
        if (currentInsertedMoney >= price) {
            currentInsertedMoney -= price;
            balanceLabel.setText("현재 잔액: " + currentInsertedMoney + "원");
            // 자바 표준 GUI 메시지 다이얼로그를 통해 성공 피드백 시각화
            JOptionPane.showMessageDialog(this, name + " 구매 성공! (잔액 복구/차감 완료)");
        } else {
            JOptionPane.showMessageDialog(this, "잔액이 부족합니다. 금액을 더 투입해 주세요.", "잔액 부족", JOptionPane.ERROR_MESSAGE);
        }
    }

    // [기능 설명] 자바 애플리케이션의 메인 진입점(Entry Point)입니다.
    public static void main(String[] args) {
        // GUI의 렌더링 무결성을 위해 이벤트 디스패치 스레드(EDT)를 가동하여 창을 화면에 띄웁니다.
        SwingUtilities.invokeLater(() -> {
            new VendingMachineForm().setVisible(true);
        });
    }
}