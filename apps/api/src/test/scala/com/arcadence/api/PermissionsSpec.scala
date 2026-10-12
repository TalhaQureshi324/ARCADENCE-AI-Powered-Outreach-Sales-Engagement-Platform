package com.arcadence.api

import munit.FunSuite

final class PermissionsSpec extends FunSuite:

  import Permissions.Permission.*
  import WorkspaceRole.*

  test("viewer can only view") {
    assertEquals(Permissions.forRole(Viewer), Set(ViewWorkspace))
  }

  test("member can view and edit content") {
    val perms = Permissions.forRole(Member)
    assert(perms.contains(ViewWorkspace))
    assert(perms.contains(EditContent))
    assert(!perms.contains(ManageMembers))
  }

  test("campaign manager adds campaign management on top of member") {
    val member = Permissions.forRole(Member)
    val manager = Permissions.forRole(CampaignManager)
    assert(manager.contains(ManageCampaigns))
    assert(!manager.contains(ManageMembers))
    assert(manager.subsetOf(member ++ Set(ManageCampaigns)))
  }

  test("admin manages members but not workspace-level danger zone") {
    val perms = Permissions.forRole(Admin)
    assert(perms.contains(ManageMembers))
    assert(perms.contains(ManageCampaigns))
    assert(!perms.contains(ManageWorkspace))
  }

  test("owner has every permission") {
    assertEquals(
      Permissions.forRole(Owner),
      Set(
        ViewWorkspace,
        EditContent,
        ManageCampaigns,
        ManageMembers,
        ManageWorkspace
      )
    )
  }

  test("role labels round-trip through fromLabel") {
    WorkspaceRole.All.foreach { role =>
      assertEquals(WorkspaceRole.fromLabel(role.label), Right(role))
    }
    assert(WorkspaceRole.fromLabel("superuser").isLeft)
  }
