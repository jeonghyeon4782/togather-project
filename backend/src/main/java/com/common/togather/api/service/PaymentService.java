package com.common.togather.api.service;

import com.common.togather.api.error.*;
import com.common.togather.api.request.TransactionSaveRequest;
import com.common.togather.api.response.PaymentFindByPlanIdAndMemberResponse;
import com.common.togather.api.response.PaymentFindByPlanIdResponse;
import com.common.togather.api.response.PaymentFindByPlanIdResponse.MemberItem;
import com.common.togather.api.response.PaymentFindByPlanIdResponse.ReceiverPayment;
import com.common.togather.api.response.PaymentFindByPlanIdResponse.SenderPayment;
import com.common.togather.api.response.PaymentFindDto;
import com.common.togather.common.util.FCMUtil;
import com.common.togather.db.entity.*;
import com.common.togather.db.repository.*;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.common.togather.common.fcm.AlarmType.*;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepositorySupport paymentRepositorySupport;
    private final PaymentRepository paymentRepository;
    private final PaymentApprovalRepositorySupport paymentApprovalRepositorySupport;
    private final PaymentApprovalRepository paymentApprovalRepository;
    private final TeamMemberRepositorySupport teamMemberRepositorySupport;
    private final PlanRepository planRepository;
    private final MemberRepository memberRepository;
    private final PayAccountRepository payAccountRepository;
    private final TransactionService transactionService;
    private final AlarmRepository alarmRepository;
    private final FCMUtil fcmUtil;

    private final String systemName = "TOGATHER";
    private final Integer systemType = 1;

    // 정산 내역 조회
    public PaymentFindByPlanIdResponse findPaymentByPlanId(String email, int teamId, int planId) {

        TeamMember teamMember = teamMemberRepositorySupport.findMemberInTeamByEmail(teamId, email)
                .orElseThrow(() -> new MemberTeamNotFoundException(teamId + "팀에 " + email + "유저가 존재하지 않습니다."));

        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new PlanNotFoundException("해당 일정은 존재하지 않습니다."));

        if (plan.getStatus() == 0) {
            throw new InvalidPlanStatusException("일정이 종료된 상태가 아닙니다.");
        }

        Team team = plan.getTeam();

        if (team.getId() != teamId) {
            throw new PlanNotFoundException("해당 팀에 속하는 일정이 존재하지 않습니다.");
        }

        Member system = memberRepository.findByNameAndType(systemName, systemType).get();

        //최종 정산 내역
        List<PaymentFindDto> paymentFindDtos = paymentRepositorySupport.findPaymentByPlanId(planId);

        Map<Integer, List<PaymentFindDto>> groupedPayments = groupPaymentsByItemId(paymentFindDtos);

        // 돈을 보내야하는 사람 (memberId, senderPayment);
        Map<Integer, SenderPayment> senderMap = new HashMap<>();
        // 돈을 받아야하는 사람 (memberId, ReceiverPayment)
        Map<Integer, ReceiverPayment> receiverMap = new HashMap<>();
        // 소비 품목 목록 (MemberItem) 
        List<MemberItem> memberItems = new ArrayList<>();

        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new MemberNotFoundException("해당 회원이 존재하지 않습니다."));

        groupedPayments.forEach((itemId, paymentFinds) -> {

            int memberBalance = getMemberBalance(paymentFinds.get(0).getPrice(), paymentFinds.size());
            int systemBalance = getSystemBalance(paymentFinds.get(0).getPrice(), paymentFinds.size(), memberBalance);

            Member receiver = paymentFinds.get(0).getReceiver();

            if (systemBalance > 0 && member.getId() == receiver.getId()) {
                if (receiverMap.containsKey(system.getId())) {
                    receiverMap.get(system.getId()).addMoney(systemBalance);
                } else {
                    receiverMap.put(
                            system.getId(),
                            ReceiverPayment.builder()
                                    .name(system.getName())
                                    .money(systemBalance)
                                    .build()
                    );
                }
            }

            for (PaymentFindDto paymentFind : paymentFinds) {
                Member sender = paymentFind.getSender();

                // 소비한 모든 품목
                if (sender.getId() == member.getId()) {
                    memberItems.add(MemberItem.builder()
                            .name(paymentFind.getItemName())
                            .money(memberBalance)
                            .build());
                }

                if (sender.getId() == receiver.getId()) {
                    continue;
                }

                // 영수증 관리자일때
                if (member.getId() == receiver.getId()) {
                    if (receiverMap.containsKey(sender.getId())) {
                        receiverMap.get(sender.getId()).addMoney(memberBalance);
                    } else {
                        receiverMap.put(
                                sender.getId(),
                                ReceiverPayment.builder()
                                        .name(sender.getNickname())
                                        .money(memberBalance)
                                        .build()
                        );

                    }
                } else if (member.getId() == sender.getId()) {
                    if (senderMap.containsKey(receiver.getId())) {
                        senderMap.get(receiver.getId()).addMoney(memberBalance);
                    } else {
                        senderMap.put(
                                receiver.getId(),
                                SenderPayment.builder()
                                        .name(receiver.getNickname())
                                        .money(memberBalance)
                                        .build()
                        );
                    }
                }
            }
        });

        System.out.println(member.getId() + " " + planId);
        // 정산 상태 얻기
        int status = paymentRepositorySupport.getStatus(member.getId(), planId);

        return PaymentFindByPlanIdResponse.builder()
                .teamTitle(team.getTitle())
                .planTitle(plan.getTitle())
                .startDate(plan.getStartDate().toString())
                .endDate(plan.getEndDate().toString())
                .status(status)
                .receiverPayments(new ArrayList<>(receiverMap.values()))
                .senderPayments(new ArrayList<>(senderMap.values()))
                .memberItems(memberItems)
                .build();
    }

    // 정산 완료
    @Transactional
    public void savePaymentByPlanId(String email, int planId) {

        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new PlanNotFoundException("해당 일정은 존재하지 않습니다."));

        if (plan.getStatus() == 3) {
            throw new InvalidPlanStatusException("이미 정산이 종료 되었습니다.");
        }

        if (plan.getStatus() != 2) {
            throw new InvalidPlanStatusException("정산 완료를 할 수 있는 상태가 아닙니다.");
        }

        // 정산 완료 상태 저장
        plan.updateStatus(3);

        //최종 정산 내역
        List<PaymentFindDto> paymentFindDtos = paymentRepositorySupport.findPaymentByPlanId(planId);

        Map<Integer, List<PaymentFindDto>> groupedPayments = groupPaymentsByItemId(paymentFindDtos);

        Map<List<Integer>, Payment> paymentMap = new HashMap<>();

        Member system = memberRepository.findByNameAndType(systemName, systemType).get();

        groupedPayments.forEach((itemId, paymentFinds) -> {

            int memberBalance = getMemberBalance(paymentFinds.get(0).getPrice(), paymentFinds.size());
            int systemBalance = getSystemBalance(paymentFinds.get(0).getPrice(), paymentFinds.size(), memberBalance);
            Member receiver = paymentFinds.get(0).getReceiver();

            if (systemBalance > 0) {
                List<Integer> key = Arrays.asList(system.getId(), receiver.getId());
                setPaymentMap(plan, paymentMap, memberBalance, system, receiver, key);
            }

            for (PaymentFindDto paymentFind : paymentFinds) {
                Member sender = paymentFind.getSender();

                if (sender.getId() == receiver.getId()) {
                    continue;
                }

                // 상쇄를 위한 key값 하나만 지정
                List<Integer> key;
                if (sender.getId() > receiver.getId()) {
                    key = Arrays.asList(receiver.getId(), sender.getId());
                    setPaymentMap(plan, paymentMap, -memberBalance, receiver, sender, key);
                } else {
                    key = Arrays.asList(sender.getId(), receiver.getId());
                    setPaymentMap(plan, paymentMap, memberBalance, sender, receiver, key);
                }
            }
        });

        // 마이너스인 경우 송금 수신 변경
        Collection<Payment> payments = paymentMap.values();

        for (Payment payment : payments) {
            if (payment.getMoney() < 0) {
                payment.switchSenderToReceiver();
                payment.updateMoney(-payment.getMoney());
            }
        }

        paymentRepository.saveAll(payments);


        List<Member> members = paymentApprovalRepositorySupport.getMembers(planId);

        // 정산할 필요 없는 member의 approval의 상태값을 2로 지정
        for (Member member : members) {
            boolean flag = false;
            for (Payment payment : payments) {
                if (member.getId() == payment.getSender().getId()) {
                    flag = true;
                    break;
                }
            }
            if (!flag) {
                updatePaymentApproval(member.getEmail(), planId);
            }
        }


        for (Member member : members) {
            // 알림 저장
            alarmRepository.save(Alarm.builder()
                    .member(member)
                    .title(PAYMENT_TRANSFER_REQUEST.getTitle())
                    .content(PAYMENT_TRANSFER_REQUEST.getMessage(plan.getTitle()))
                    .type(PAYMENT_TRANSFER_REQUEST.getType())
                    .tId(plan.getTeam().getId())
                    .pId(planId)
                    .build());

            // 알림 전송
            fcmUtil.pushNotification(
                    member.getFcmToken(),
                    PAYMENT_TRANSFER_REQUEST.getTitle(),
                    PAYMENT_TRANSFER_REQUEST.getMessage(plan.getTitle())
            );
        }
    }

    // 실시간 정산 현황
    public PaymentFindByPlanIdAndMemberResponse findPaymentByPlanIdAndMember(String email, int planId) {

        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new PlanNotFoundException("해당 일정은 존재하지 않습니다."));

        if (plan.getStatus() != 0) {
            throw new InvalidPlanStatusException("일정이 종료 되었습니다.");
        }

        //최종 정산 내역
        List<PaymentFindDto> paymentFindDtos = paymentRepositorySupport.findPaymentByPlanId(planId);

        Map<Integer, List<PaymentFindDto>> groupedPayments = groupPaymentsByItemId(paymentFindDtos);

        // meberId, money (사용자가 보내야하는 금액이 기준)
        Map<Integer, Integer> paymentMap = new HashMap<>();

        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new MemberNotFoundException("해당 회원이 존재하지 않습니다."));

        groupedPayments.forEach((itemId, paymentFinds) -> {

            int memberBalance = getMemberBalance(paymentFinds.get(0).getPrice(), paymentFinds.size());
            Member receiver = paymentFinds.get(0).getReceiver();

            for (PaymentFindDto paymentFind : paymentFinds) {
                Member sender = paymentFind.getSender();

                if (sender.getId() == receiver.getId()) {
                    continue;
                }

                // 영수증 관리자일때
                if (member.getId() == receiver.getId()) {
                    paymentMap.put(sender.getId(),
                            paymentMap.getOrDefault(sender.getId(), 0) + memberBalance);
                } else if (member.getId() == sender.getId()) {
                    paymentMap.put(receiver.getId(),
                            paymentMap.getOrDefault(receiver.getId(), 0) - memberBalance);
                }
            }
        });

        // 유저가 보내고 받는 금액 합
        int total = 0;
        for (int money : paymentMap.values()) {
            total += money;
        }

        return PaymentFindByPlanIdAndMemberResponse.builder()
                .money(total)
                .build();
    }

    private void setPaymentMap(Plan plan, Map<List<Integer>, Payment> paymentMap, int memberBalance, Member sender,
                               Member receiver, List<Integer> key) {
        if (paymentMap.containsKey(key)) {
            int currentMoney = paymentMap.get(key).getMoney();
            paymentMap.put(key, Payment.builder()
                    .plan(plan)
                    .sender(sender)
                    .receiver(receiver)
                    .money(memberBalance + currentMoney)
                    .build());
        } else {
            paymentMap.put(key, Payment.builder()
                    .plan(plan)
                    .sender(sender)
                    .receiver(receiver)
                    .money(memberBalance)
                    .build());
        }
    }

    private int getSystemBalance(int amount, int count, int memberBalance) {
        return amount - (memberBalance * count);
    }

    private int getMemberBalance(int amount, int count) {
        return amount / count;
    }

    public Map<Integer, List<PaymentFindDto>> groupPaymentsByItemId(List<PaymentFindDto> payments) {
        return payments.stream()
                .collect(Collectors.groupingBy(PaymentFindDto::getItemId));
    }

    // 정산내역 송금하기
    @Transactional
    public void transferSettlement(String email, int planId) {

        // 송금할 Payment 목록 조회
        List<Payment> payments = paymentRepository.findByPlanIdAndSenderEmail(planId, email);
        if (payments.isEmpty()) {
            updatePaymentApproval(email, planId);
            throw new PaymentNotFoundException("정산할 내역이 없습니다.");
        }

        // 송금자의 Pay 계좌 확인
        PayAccount payAccount = payAccountRepository.findByMember_Email(email)
                .orElseThrow(() -> new PayAccountPaymentNotFoundException("사용자의 Pay 계좌가 존재하지 않습니다."));
        Member member = payAccount.getMember();

        // 총 송금할 금액 계산
        int totalAmount = payments.stream().mapToInt(Payment::getMoney).sum();
        if (payAccount.getBalance() < totalAmount) {
            throw new InsufficientBalanceException("잔액이 부족합니다.");
        }

        // 송금 대상자의 Pay 계좌 미리 조회 및 캐싱
        Map<Integer, PayAccount> targetPayAccounts = new HashMap<>();
        for (Payment payment : payments) {
            int receiverId = payment.getReceiver().getId();
            targetPayAccounts.putIfAbsent(receiverId,
                    payAccountRepository.findByMemberId(receiverId)
                            .orElseThrow(() -> new PayAccountPaymentNotFoundException("Target Pay 계좌가 존재하지 않습니다.")));
        }

        List<TransactionSaveRequest> transactionRequests = new ArrayList<>();

        // 송금 처리 및 거래 내역 생성
        for (Payment payment : payments) {
            PayAccount targetPayAccount = targetPayAccounts.get(payment.getReceiver().getId());
            Member targetMember = targetPayAccount.getMember();

            payAccount.decreaseBalance(payment.getMoney());
            targetPayAccount.increaseBalance(payment.getMoney());

            // 송금자 거래 내역 생성
            transactionRequests.add(TransactionSaveRequest.builder()
                    .senderName(member.getName())
                    .receiverName(targetMember.getName())
                    .price(payment.getMoney())
                    .balance(payAccount.getBalance())
                    .date(LocalDateTime.now())
                    .status(1)  // 출금
                    .payAccountId(payAccount.getId())
                    .build());

            // 수신자 거래 내역 생성
            transactionRequests.add(TransactionSaveRequest.builder()
                    .senderName(member.getName())
                    .receiverName(targetMember.getName())
                    .price(payment.getMoney())
                    .balance(targetPayAccount.getBalance())
                    .date(LocalDateTime.now())
                    .status(0)  // 입금
                    .payAccountId(targetPayAccount.getId())
                    .build());

            // 송금인 알림 저장
            alarmRepository.save(Alarm.builder()
                    .member(member)
                    .title(WITHDRAWAL_ALERT.getTitle())
                    .content(WITHDRAWAL_ALERT.getMessage(targetMember.getName(), String.valueOf(payment.getMoney())))
                    .type(WITHDRAWAL_ALERT.getType())
                    .mId(member.getId())
                    .build());

            // 송금인 알림 전송
            fcmUtil.pushNotification(
                    member.getFcmToken(),
                    WITHDRAWAL_ALERT.getTitle(),
                    WITHDRAWAL_ALERT.getMessage(targetMember.getName(), String.valueOf(payment.getMoney()))
            );

            // 수취인 알림 저장
            alarmRepository.save(Alarm.builder()
                    .member(targetMember)
                    .title(PAYACOUNT_RECEIVED.getTitle())
                    .content(PAYACOUNT_RECEIVED.getMessage(member.getName(), String.valueOf(payment.getMoney())))
                    .type(PAYACOUNT_RECEIVED.getType())
                    .mId(member.getId())
                    .build());

            // 수취인 알림 전송
            fcmUtil.pushNotification(
                    targetMember.getFcmToken(),
                    PAYACOUNT_RECEIVED.getTitle(),
                    PAYACOUNT_RECEIVED.getMessage(member.getName(), String.valueOf(payment.getMoney()))
            );
        }

        // 거래 내역 배치 저장
        transactionService.saveTransactions(transactionRequests);

        // 정산 내역 배치 삭제
        paymentRepository.deleteAll(payments);

        // 모든 Payment가 처리된 경우 계획 상태 업데이트
        if (paymentRepository.countByPlanId(planId) == 0) {
            Plan plan = planRepository.findById(planId).orElseThrow(() -> new PlanNotFoundException("일정을 찾을 수 없습니다."));
            plan.updateStatus(4);
            planRepository.save(plan);
        }

        updatePaymentApproval(email, planId);
    }

    private void updatePaymentApproval(String email, int planId) {
        PaymentApproval paymentApproval = paymentApprovalRepository.findByMemberEmailAndPlanId(email, planId)
                .orElseThrow(() -> new NotFoundPaymentApprovalException("해당 정산 요청이 없습니다."));
        paymentApproval.updateStatus(2);
    }
}
