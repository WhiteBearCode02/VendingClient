/*
 * CircularQueue.java
 * ------------------
 * 네트워크 통신 패킷을 비동기로 버퍼링하기 위한 원형 큐 구현입니다.
 * 요구사항 중 'Stack과 Queue 적절히 사용' 항목에서 Queue 사용을 만족하며,
 * 추가 기능으로 멀티스레드 생산자-소비자 패턴을 지원합니다.
 */

// [기능 설명] 멀티스레드 환경에서 안전하게 문자열 패킷을 버퍼링하는 원형 큐 자료구조 클래스입니다.
// 고정된 크기의 배열 내에서 front와 rear 인덱스를 순환시켜 메모리 효율을 극대화합니다.
public class CircularQueue {
    private String[] queue;
    private int size;
    private int front = 0;
    private int rear = 0;

    // [기능 설명] 큐의 고정 크기를 지정하여 메모리 공간을 확보하는 생성자입니다.
    public CircularQueue(int capacity) {
        this.size = capacity;
        this.queue = new String[capacity];
    }

    // [기능 설명] 생산자(UI 스레드)가 판매/취소 패킷 문자열을 큐의 rear 위치에 적재합니다.
    // 임계 구역(Critical Section) 보호를 위해 synchronized 키워드로 상호 배제를 강제합니다.
    public synchronized void enqueue(String message) {
        // 큐가 가득 찼는지(Full) 검사하는 Modulo(나머지) 연산 알고리즘
        if ((rear + 1) % size == front) {
            System.out.println("[큐 오버플로우] 네트워크 버퍼가 가득 차 데이터 전송이 지연됩니다.");
            return;
        }
        queue[rear] = message;
        rear = (rear + 1) % size; // rear 포인터를 원형 궤도로 한 칸 전진
    }

    // [기능 설명] 소비자(통신 워커 스레드)가 큐의 front 위치에서 패킷을 꺼내 반환합니다.
    // 마찬가지로 동시 접근 시 데이터 변조를 막기 위해 동기화 락(Lock)이 가동됩니다.
    public synchronized String dequeue() {
        // 큐가 비어있는지(Empty) 여부를 판별하는 조건문
        if (front == rear) {
            return null; // 꺼낼 데이터가 없음을 의미
        }
        String message = queue[front];
        front = (front + 1) % size; // front 포인터를 원형 궤도로 한 칸 전진
        return message;
    }

    // [기능 설명] 현재 큐가 완전히 비어있는 상태인지 외부 스레드가 감시하기 위한 함수입니다.
    public synchronized boolean isEmpty() {
        return front == rear;
    }
}