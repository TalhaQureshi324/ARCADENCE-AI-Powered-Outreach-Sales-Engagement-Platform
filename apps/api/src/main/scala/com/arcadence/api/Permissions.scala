package com.arcadence.api

/** Workspace roles, mirroring the `workspace_role` Postgres enum. */
object WorkspaceRole:
  sealed trait Role extends Product with Serializable:
    def label: String

  case object Owner extends Role { val label = "owner" }
  case object Admin extends Role { val label = "admin" }
  case object CampaignManager extends Role { val label = "campaign_manager" }
  case object Member extends Role { val label = "member" }
  case object Viewer extends Role { val label = "viewer" }

  val All: List[Role] = List(Owner, Admin, CampaignManager, Member, Viewer)

  /** Parses a role label from the database. Unknown labels are a data error. */
  def fromLabel(label: String): Either[String, Role] =
    All.find(_.label == label).toRight(s"Unknown workspace role: $label")

/** Explicit permission model. Authorization checks name permissions, never
  * roles — role-to-permission mapping lives here and only here
  * (docs/ARCHITECTURE.md §3).
  */
object Permissions:
  sealed trait Permission extends Product with Serializable:
    def label: String

  object Permission:
    case object ViewWorkspace extends Permission {
      val label = "view_workspace"
    }
    case object EditContent extends Permission { val label = "edit_content" }
    case object ManageCampaigns extends Permission {
      val label = "manage_campaigns"
    }
    case object ManageMembers extends Permission {
      val label = "manage_members"
    }
    case object ManageWorkspace extends Permission {
      val label = "manage_workspace"
    }

  import Permission.*

  private val ViewerMapping: Set[Permission] = Set(ViewWorkspace)
  private val MemberMapping: Set[Permission] = ViewerMapping + EditContent
  private val CampaignManagerMapping: Set[Permission] =
    MemberMapping + ManageCampaigns
  private val AdminMapping: Set[Permission] =
    CampaignManagerMapping + ManageMembers
  private val OwnerMapping: Set[Permission] = AdminMapping + ManageWorkspace

  def forRole(role: WorkspaceRole.Role): Set[Permission] = role match
    case WorkspaceRole.Owner           => OwnerMapping
    case WorkspaceRole.Admin           => AdminMapping
    case WorkspaceRole.CampaignManager => CampaignManagerMapping
    case WorkspaceRole.Member          => MemberMapping
    case WorkspaceRole.Viewer          => ViewerMapping
