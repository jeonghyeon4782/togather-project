import React, { useEffect, useState } from "react";
import GameModal from "./GameModal";
import ResultModal from "./ResultModal";
import "./Game.css";
import Machine from "../../assets/game/machine.png";
import MachineTongsHead from "../../assets/game/machine-tongs-head.png";
import MachineTongs from "../../assets/game/machine-tongs.png";
import AddButton from "../../assets/icons/common/add.png";
import SelectedCard from "../../assets/game/selectedCard.png";
import DefaultProfile from "../../assets/icons/common/defaultProfile.png";
import Modal from "../common/Modal";

function GameContainer() {
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [isResultModalOpen, setIsResultModalOpen] = useState(false);
  const [selectedTeam, setSelectedTeam] = useState(null);
  const [participants, setParticipants] = useState([]);
  const [randomParticipant, setRandomParticipant] = useState(null);
  const [isTongsDown, setIsTongsDown] = useState(false);
  const [noParticipant, SetNoParticipant] = useState(false);


  const addParticipant = () => {
    // 참여 인원 리셋
    setParticipants([]);
    setSelectedTeam(null);
    setIsModalOpen(true);
  };

  const handleConfirm = (team, selectedParticipants) => {
    setSelectedTeam(team); // 선택된 팀 저장
    setParticipants(selectedParticipants); // 선택된 참여자들을 저장
    setIsModalOpen(false);
  };

  const handleCardPick = () => {
    if (participants.length === 0) {
      // 참여자 없을 시 모달 알림
      SetNoParticipant(true);
    } else {
      SetNoParticipant(false);
      setIsTongsDown(true);
      setTimeout(() => {
        setIsTongsDown(false);
      }, 1500);

      setTimeout(() => {
        const randomIndex = Math.floor(Math.random() * participants.length);
        setRandomParticipant(participants[randomIndex]);
        setIsResultModalOpen(true);
      }, 3000);
    }
  };

  return (
    <div className="game-container">
      <h1>누가 걸리게 될까요?</h1>
      <div className="game-participants">참여 인원</div>
      <div
        style={{ display: "flex", alignItems: "center" }}
        className="game-participants-list"
      >
        <img
          src={AddButton}
          onClick={addParticipant}
          className="game-add-button"
          alt="Add"
        />
        {participants.map((participant) => (
          <div key={participant.memberId} className="game-participant">
            <img
              src={
                participant.profileImg ? participant.profileImg : DefaultProfile
              }
              alt=""
              className="game-participant-image"
            />
          </div>
        ))}
      </div>
      <div className="game-machine-container">
        <img src={Machine} className="game-machine" alt="machine" />
        <img
          src={MachineTongsHead}
          className="game-machine-tongs-head"
          alt="head"
        />
        <img
          src={MachineTongs}
          className={`game-machine-tongs ${
            isTongsDown ? "tongs-down" : "tongs-up"
          }`}
          alt="tongs"
        />
        <img
          src={SelectedCard}
          className={`game-machine-selected-card ${
            !isTongsDown ? "selected-card-up" : ""
          }`}
          alt=""
        />
        <button className="game-start-button" onClick={handleCardPick}>
          카드 뽑기
        </button>
      </div>
      {isModalOpen && (
        <GameModal
          onClose={() => setIsModalOpen(false)}
          onConfirm={handleConfirm}
        />
      )}
      {isResultModalOpen && (
        <ResultModal
          onClose={() => setIsResultModalOpen(false)}
          selectedParticipant={randomParticipant}
          selectedTeam={selectedTeam}
        />
      )}
      {noParticipant && (
        <Modal
          mainMessage="선택된 참여 인원이 없습니다"
          subMessage="게임 참여 인원을 선택해보세요"
          onClose={() => SetNoParticipant(false)}
        />
      )}
    </div>
  );
}

export default GameContainer;
