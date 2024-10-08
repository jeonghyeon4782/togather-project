import React, { useEffect, useState } from "react";
import axios from "axios";
import { v4 as uuidv4 } from "uuid";
import Loading from "../../../common/Loading";
import Modal from "../../../common/Modal";

const RECEIPT_API_URL = "/ocr/receipt";

function OcrComponent({ image, onOcrResult }) {
  const [isLoading, setIsLoading] = useState(false); // 로딩 상태 관리
  const [error, setError] = useState(false); // 에러 상태 관리

  useEffect(() => {
    const fetchOcrResult = async () => {
      // OCR 인식 시에 Loading 화면
      setIsLoading(true);
      try {
        const response = await axios.post(
          RECEIPT_API_URL,
          {
            version: "V2",
            requestId: uuidv4(),
            timestamp: Date.now(),
            images: [
              {
                format: "jpg",
                data: image.split(",")[1],
                name: "receipt", // 임시 name
              },
            ],
          },
          {
            headers: {
              "Content-Type": "application/json",
              "X-OCR-SECRET": import.meta.env.VITE_OCR_API_SECRET_KEY, // 환경변수에서 가져온 Secret 키
            },
          }
        );
        // OCR 결과를 파싱
        const parsedResult = parseOcrResult(response.data);
        // 파싱된 result를 반환
        onOcrResult(parsedResult);
      } catch (error) {
        setError(true);
      } finally {
        setIsLoading(false);
      }
    };

    // OCR 결과를 파싱하는 함수
    const parseOcrResult = (data) => {
      // OCR 결과에서 영수증 결과 가져옴
      const result = data.images[0].receipt.result;

      // 상호명과 총액을 추출하고 인식되지 않으면 기본값으로 설정
      const businessName = result.storeInfo?.name?.text || "장소명";
      const totalPrice =
        parseInt(result.totalPrice?.price.formatted?.value || 0) || 0;

      // 결제 일시
      let paymentDate;

      // 결제 일시 검증하는 함수
      function isValidDate(year, month, day) {
        // 년, 월, 일이 숫자인지 확인하고 유효한 범위 내에 있는지 검사
        if (
          typeof year !== "number" ||
          year < 1 ||
          typeof month !== "number" ||
          month < 1 ||
          month > 12 ||
          typeof day !== "number" ||
          day < 1 ||
          day > 31
        ) {
          return false;
        }

        // 주어진 년, 월, 일로 날짜 객체를 생성
        const date = new Date(year, month - 1, day);

        // 생성된 날짜 객체가 유효한지 확인하고 입력값과 생성된 날짜의 값이 일치하는지 확인
        return (
          date.getFullYear() === year &&
          date.getMonth() === month - 1 &&
          date.getDate() === day
        );
      }

      // 결제 일시가 인식되었는지 확인
      if (result.paymentInfo?.date?.formatted) {
        const { year, month, day } = result.paymentInfo.date.formatted;
        // 결제 일시 유효성 검사
        if (isValidDate(year, month, day)) {
          // 유효한 경우, 결제 일시를 설정
          paymentDate = `${year}/${month}/${day}`;
        } else {
          // 유효하지 않은 경우, 현재 날짜로 설정
          paymentDate = new Date().toISOString().split("T")[0];
        }
      } else {
        // 결제 일시가 인식되지 않은 경우, 현재 날짜로 설정
        paymentDate = new Date().toISOString().split("T")[0];
      }

      // 품목을 설정합니다. 인식되지 않으면 기본 값으로 설정
      const items = result.subResults[0]?.items.map((item) => ({
        name: item.name?.text || "품목명",
        count: parseInt(item?.count?.text || 1) || 1,
        unitPrice: parseInt(item?.price?.price?.formatted.value, 0) || 0,
      })) || [{ name: "품목 없음", count: 1, unitPrice: totalPrice }];

      return {
        businessName,
        paymentDate,
        items,
      };
    };

    // 이미지가 넘어온 경우, Ocr 결과 가져오기
    if (image) {
      fetchOcrResult();
    }
  }, [image, onOcrResult]);

  return (
    <div>
      {isLoading && (
        <Loading>
          <span style={{ color: "#712FFF" }}>결제 내용</span>을<br /> 분석
          중이에요
        </Loading>
      )}
      {error && (
        <Modal
          mainMessage="문제가 발생했습니다."
          subMessage="다시 시도해보세요."
          onClose={() => setError(false)}
        />
      )}
    </div>
  );
}

export default OcrComponent;
