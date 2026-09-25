"""
Modèles SQLAlchemy : kb_chunk, conversation_log, ...
"""
from sqlalchemy import Column, BigInteger, Integer, Text
from sqlalchemy.dialects.postgresql import UUID
from pgvector.sqlalchemy import Vector
from sqlalchemy.orm import declarative_base

Base = declarative_base()


class KbChunk(Base):
    """
    Correspond exactement à la table kb_chunk déjà créée par Fedi
    (colonnes vérifiées sur Neon : id, article_id, chunk_index, content, embedding).
    """
    __tablename__ = "kb_chunk"

    id = Column(BigInteger, primary_key=True)
    article_id = Column(UUID(as_uuid=True), nullable=False)  # FK vers kb_article.id
    chunk_index = Column(Integer, nullable=False)
    content = Column(Text, nullable=False)
    embedding = Column(Vector(384), nullable=False)