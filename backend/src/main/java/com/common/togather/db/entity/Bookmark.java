package com.common.togather.db.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Bookmark {

    // pk
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    // 일정
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private Plan plan;

    // 장소 id
    @Column(name = "place_id", nullable = false)
    private String placeId;

    // 장소 사진
    @Column(name = "place_img", nullable = true)
    private String placeImg;

    // 장소명
    @Column(name = "place_name", nullable = false)
    private String placeName;

    // 장소 주소
    @Column(name = "place_addr", nullable = true)
    private String placeAddr;

    // 일자
    @Column(name = "date", nullable = true)
    private LocalDate date;

    // 순서
    @Column(name = "item_order", nullable = true)
    private Integer itemOrder;

    // 영수증
    @OneToMany(mappedBy = "bookmark", cascade = CascadeType.ALL)
    private List<Receipt> receipts;

    // 날짜 해제로 찜 리스트로 이동한 경우
    public void moveToJjim() {
        this.date = null;
        this.itemOrder = 0;
    }

    // 찜 리스트에서 계획으로 이동한 경우 또는 날짜 있는데 다른 날짜로 이동한 경우
    public void updateDate(LocalDate date, int targetSize) {
        this.date = date;
        this.itemOrder = targetSize;
    }

    // 순서를 변경한 경우
    public void updateOrder(Integer itemOrder) {
        this.itemOrder = itemOrder;
    }
}


