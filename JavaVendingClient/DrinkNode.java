/*
 * DrinkNode.java
 * ------------------
 * 자판기 음료 재고를 단일 연결 리스트(Linked-List) 형태로 구현한 노드 클래스입니다.
 * 요구사항 중 '각 음료의 재고 수량은 Linked-List를 통해 구현' 항목을 만족합니다.
 * 추가 기능: 연결 리스트 재고 모델을 통해 재고 보충, 조회, 음료 정보 변경을 자연스럽게 처리합니다.
 */

// [기능 설명] C언어 vending_machine.h의 'struct DrinkNode' 구조체를 Java의 객체지향 클래스로 완벽하게 이식합니다.
public class DrinkNode {
    // 캡슐화(Encapsulation) 원칙에 따라 데이터 필드를 보호합니다.
    private String name; // 음료 이름
    private int price; // 음료 가격
    private int stock; // 현재 남은 재고 수량
    private DrinkNode next; // 다음 음료 노드를 가리키는 자바 참조 변수 (C언어의 포인터 *next 대체)

    // [기능 설명] 새로운 음료 노드를 메모리에 생성할 때 데이터 값을 초기화하는 생성자입니다.
    public DrinkNode(String name, int price, int stock) {
        this.name = name;
        this.price = price;
        this.stock = stock;
        this.next = null; // 초기화 시점에는 다음 노드가 연결되지 않은 고립 상태
    }

    // [기능 설명] 정보 은닉(Data Hiding)을 준수하기 위한 Getter 및 Setter 메서드들입니다.
    public String getName() {
        return name;
    }

    public int getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setPrice(int price) {
        this.price = price;
    }

    public DrinkNode getNext() {
        return next;
    }

    public void setNext(DrinkNode next) {
        this.next = next;
    }
}