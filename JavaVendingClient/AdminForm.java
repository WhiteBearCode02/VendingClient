// [기능 설명] 자판기의 매출 관리, 재고 보충, 상품 정보 수정을 전담하는 독립적인 관리자 GUI 창 클래스입니다.
// 파일 입출력 및 배열 정렬 알고리즘 소스코드가 집약되어 있습니다.

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;

public class AdminForm extends JFrame {
    // 의존성 결합: 자판기의 연결 리스트 인벤토리를 직접 제어하기 위해 메인 폼의 참조 주소를 보관합니다.
    private VendingMachineForm mainForm;
    private JTextArea logArea;

    // [매출 데이터 구조체 역할] 파일에서 읽어온 데이터를 정렬하기 위한 내부 정적 클래스입니다.
    static class SalesData {
        String name;
        int price;

        public SalesData(String name, int price) {
            this.name = name;
            this.price = price;
        }
    }

    // [핵심 해결] 매개변수 이름을 'form'으로 변경하여 Variable Shadowing(변수 가림 현상)을 방지합니다.
    public AdminForm(VendingMachineForm form) {
        this.mainForm = form; // 이제 클래스의 진짜 필드인 mainForm에 안전하게 안착합니다.

        setTitle("자판기 중앙 관리자 시스템");
        setSize(500, 600);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE); // 관리자 창만 닫히고 메인 자판기는 유지되도록 설정
        setLayout(new BorderLayout(10, 10));

        JLabel titleLabel = new JLabel("시스템 관리자 세션", SwingConstants.CENTER);
        titleLabel.setFont(new Font("맑은 고딕", Font.BOLD, 20));
        add(titleLabel, BorderLayout.NORTH);

        // 중앙 영역: 매출 내역 파일 로그를 시각화할 스크롤 텍스트 영역
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("돋움", Font.PLAIN, 13));
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        // 하단 영역: 제어 기능 버튼 모음 패널
        JPanel controlPanel = new JPanel(new GridLayout(3, 2, 5, 5));

        JButton btnLoadSales = new JButton("1. 매출 내역 정렬 확인 (Sort)");
        JButton btnSearchPrice = new JButton("2. 특정 가격 상품 검색 (BST)");
        JButton btnUpdateInfo = new JButton("3. 음료 정보 수정");
        JButton btnReplenish = new JButton("4. 음료 재고 보충");
        JButton btnChangePassword = new JButton("5. 관리자 비밀번호 변경");

        // [★ 알고리즘 통합 ★] 1. 파일에서 매출을 읽어와 버블 정렬 알고리즘을 가동하는 이벤트 리스너
        btnLoadSales.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                displaySortedSalesFromFile();
            }
        });

        // 2. 이진 탐색 트리 검색 브릿지 호출
        btnSearchPrice.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String targetStr = JOptionPane.showInputDialog(null, "검색할 음료의 타겟 가격을 입력하세요:");
                if (targetStr != null && !targetStr.isEmpty()) {
                    mainForm.buildTreeAndSearchPrice(Integer.parseInt(targetStr));
                }
            }
        });

        // 3. 연결 리스트 노드 정보 수정 연동
        btnUpdateInfo.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String oldName = JOptionPane.showInputDialog("변경할 기존 음료 이름:");
                String newName = JOptionPane.showInputDialog("새로운 음료 이름:");
                String newPriceStr = JOptionPane.showInputDialog("새로운 가격:");
                if (oldName != null && newName != null && newPriceStr != null) {
                    mainForm.updateDrinkInfo(oldName, newName, Integer.parseInt(newPriceStr));
                    JOptionPane.showMessageDialog(null, "음료 정보 변경이 완료되었습니다. 메인 화면을 확인하세요.");
                }
            }
        });

        // 4. 연결 리스트 노드 재고 누적 연동
        btnReplenish.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String name = JOptionPane.showInputDialog("재고를 보충할 음료 이름:");
                String amountStr = JOptionPane.showInputDialog("보충 수량:");
                if (name != null && amountStr != null) {
                    mainForm.replenishStock(name, Integer.parseInt(amountStr));
                    JOptionPane.showMessageDialog(null, "재고 보충이 완료되었습니다.");
                }
            }
        });

        // 5. 관리자 비밀번호 변경 기능
        btnChangePassword.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String currentPwd = JOptionPane.showInputDialog("현재 관리자 비밀번호를 입력하세요:");
                if (currentPwd == null)
                    return;

                String newPwd = JOptionPane.showInputDialog("새 관리자 비밀번호를 입력하세요 (특수문자+숫자 포함, 8자리 이상):");
                if (newPwd == null)
                    return;

                String confirmPwd = JOptionPane.showInputDialog("새 비밀번호를 다시 입력하세요:");
                if (confirmPwd == null)
                    return;

                if (!newPwd.equals(confirmPwd)) {
                    JOptionPane.showMessageDialog(null, "새 비밀번호가 서로 일치하지 않습니다.", "변경 실패", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                String result = mainForm.changeAdminPassword(currentPwd, newPwd);
                if (result == null) {
                    JOptionPane.showMessageDialog(null, "관리자 비밀번호가 성공적으로 변경되었습니다.");
                } else {
                    JOptionPane.showMessageDialog(null, result, "변경 실패", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        controlPanel.add(btnLoadSales);
        controlPanel.add(btnSearchPrice);
        controlPanel.add(btnUpdateInfo);
        controlPanel.add(btnReplenish);
        controlPanel.add(btnChangePassword);
        add(controlPanel, BorderLayout.SOUTH);
    }

    // [기능 설명] sales.txt 로컬 파일을 읽어 메모리 배열에 올린 뒤 버블 정렬을 수행하여 출력합니다.
    private void displaySortedSalesFromFile() {
        ArrayList<SalesData> list = new ArrayList<>();
        // 자바의 파일 코어 스트림 개통 (BufferedReader를 통한 줄 단위 고속 읽기)
        try (BufferedReader br = new BufferedReader(new FileReader("sales.txt"))) {
            String line;
            while ((line = br.readLine()) != null) {
                // 저장 포맷팅 분리 파싱: "SALE|음료명|가격"
                String[] tokens = line.split("\\|");
                if (tokens.length >= 3 && tokens[0].equals("SALE")) {
                    list.add(new SalesData(tokens[1], Integer.parseInt(tokens[2])));
                }
            }
        } catch (Exception ex) {
            logArea.setText("[안내] 아직 저장된 누적 매출 파일 기록이 존재하지 않습니다.");
            return;
        }

        // 동적 리스트를 정렬 알고리즘용 고정 정적 배열로 변환
        SalesData[] arr = list.toArray(new SalesData[0]);

        // [핵심 정렬 알고리즘] 가격 오름차순 기준 버블 정렬(Bubble Sort)을 실행합니다. (O(n^2))
        for (int i = 0; i < arr.length - 1; i++) {
            for (int j = 0; j < arr.length - i - 1; j++) {
                if (arr[j].price > arr[j + 1].price) {
                    // 객체 참조 주소 Swap 교환 작업 수행
                    SalesData temp = arr[j];
                    arr[j] = arr[j + 1];
                    arr[j + 1] = temp;
                }
            }
        }

        // 정렬된 파일 데이터를 텍스트 영역에 시각화 출력
        StringBuilder sb = new StringBuilder();
        sb.append("--- [로컬 sales.txt 데이터 정렬 조회 결과] ---\n");
        int total = 0;
        for (int i = 0; i < arr.length; i++) {
            sb.append(String.format("%d. 상품명: %s | 가격: %d원\n", i + 1, arr[i].name, arr[i].price));
            total += arr[i].price;
        }
        sb.append("----------------------------------------------------\n");
        sb.append(">> 시스템 누적 총 합산 매출액: ").append(total).append("원\n");
        logArea.setText(sb.toString());
    }
}