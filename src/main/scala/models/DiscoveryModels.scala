package models

import java.nio.file.Path
import java.time.Instant

import zio.json.*

import Codecs.given

enum FileType derives JsonCodec:
  case Program, Copybook, JCL

case class CobolFile(
  path: Path,
  name: String,
  size: Long,
  lineCount: Long,
  lastModified: Instant,
  encoding: String,
  fileType: FileType,
) derives JsonCodec

case class InventorySummary(
  totalFiles: Int,
  programFiles: Int,
  copybooks: Int,
  jclFiles: Int,
  totalLines: Long,
  totalBytes: Long,
) derives JsonCodec

case class FileInventory(
  discoveredAt: Instant,
  sourceDirectory: Path,
  files: List[CobolFile],
  summary: InventorySummary,
) derives JsonCodec
