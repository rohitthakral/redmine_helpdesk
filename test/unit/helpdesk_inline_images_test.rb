require File.dirname(__FILE__) + '/../test_helper'
require 'tmpdir'

# Screenshots in outgoing emails must be embedded (cid:) so clients see them
# in the body instead of a login-only link or a broken image.
class HelpdeskInlineImagesTest < ActionMailer::TestCase
  self.use_transactional_tests = true

  fixtures :all

  # 1x1 transparent PNG
  PNG = ['89504e470d0a1a0a0000000d4948445200000001000000010806000000' \
         '1f15c4890000000d49444154789c6360000002000154a24f5d0000000049454e44ae426082'].pack('H*')

  def setup
    ActionMailer::Base.deliveries.clear
    Setting.host_name = 'mydomain.foo'
    Setting.protocol = 'http'
    Setting.plain_text_mail = '0'
    Setting.default_language = 'en'
    Setting.text_formatting = 'textile'
    User.current = nil
    @storage_path = Attachment.storage_path
    Attachment.storage_path = Dir.mktmpdir
    @issue = Issue.find(1)
    @image = create_image(@issue, 'screenshot.png')
  end

  def teardown
    FileUtils.rm_rf(Attachment.storage_path)
    Attachment.storage_path = @storage_path
    Setting.clear_cache
  end

  def test_helpdesk_mail_embeds_textile_image
    mail = deliver_to_supportclient("See !screenshot.png!")
    assert_embedded mail, @image
  end

  def test_helpdesk_mail_embeds_markdown_image
    Setting.text_formatting = 'common_mark'
    mail = deliver_to_supportclient("See ![](screenshot.png)")
    assert_embedded mail, @image
  end

  def test_helpdesk_mail_embeds_full_attachment_url
    # the WYSIWYG editor writes full download URLs (and raw <img> tags)
    mail = deliver_to_supportclient(
      "See !http://mydomain.foo/attachments/download/#{@image.id}/screenshot.png!")
    assert_embedded mail, @image
  end

  def test_helpdesk_mail_does_not_attach_embedded_image_twice
    journal = Journal.create!(:journalized => @issue, :user => User.find(1), :notes => 'x')
    JournalDetail.create!(:journal => journal, :property => 'attachment',
                          :prop_key => @image.id.to_s, :value => @image.filename)
    mail = HelpdeskMailer.email_to_supportclient(
      @issue, :recipient => 'owner@example.com', :journal => journal, :text => 'See !screenshot.png!'
    ).deliver
    assert_equal 1, mail.attachments.size
    assert mail.attachments.first.inline?
  end

  def test_helpdesk_mail_links_only_files_not_shown_inline
    doc = Attachment.create!(:container => @issue, :author => User.find(1),
                             :file => Rack::Test::UploadedFile.new(
                               File.join(Dir.mktmpdir, 'report.txt').tap { |p| File.write(p, 'r') },
                               'text/plain'))
    journal = Journal.create!(:journalized => @issue, :user => User.find(1), :notes => 'x')
    [@image, doc].each do |a|
      JournalDetail.create!(:journal => journal, :property => 'attachment',
                            :prop_key => a.id.to_s, :value => a.filename)
    end
    mail = HelpdeskMailer.email_to_supportclient(
      @issue, :recipient => 'owner@example.com', :journal => journal, :text => 'See !screenshot.png!'
    ).deliver
    assert_equal [['report.txt', false], ['screenshot.png', true]],
                 mail.attachments.map { |a| [a.filename, a.inline?] }.sort
    [mail.text_part.decoded, mail.html_part.decoded].each do |part|
      assert_include "/helpdesk/attachments/#{doc.id}/", part
      assert_not_include "/helpdesk/attachments/#{@image.id}/", part
    end
  end

  def test_helpdesk_mail_without_images_stays_plain_text
    mail = deliver_to_supportclient("No pictures here")
    assert !mail.multipart?
  end

  def test_interceptor_embeds_attachment_url_in_html_part
    mail = build_mail(@issue,
      %(<p><img src="http://mydomain.foo/attachments/download/#{@image.id}/screenshot.png" alt=""></p>))
    HelpdeskInlineImages.delivering_email(mail)
    assert_embedded mail, @image
  end

  def test_interceptor_ignores_attachments_of_other_projects
    other = create_image(Issue.where.not(:project_id => @issue.project_id).first, 'secret.png')
    src = "http://mydomain.foo/attachments/download/#{other.id}/secret.png"
    mail = build_mail(@issue, %(<img src="#{src}">))
    HelpdeskInlineImages.delivering_email(mail)
    assert_include src, mail.html_part.decoded
    assert_equal 0, mail.attachments.size
  end

  def test_interceptor_ignores_other_hosts
    src = "http://elsewhere.example/attachments/download/#{@image.id}/screenshot.png"
    mail = build_mail(@issue, %(<img src="#{src}">))
    HelpdeskInlineImages.delivering_email(mail)
    assert_include src, mail.html_part.decoded
  end

  private

  def create_image(issue, filename)
    path = File.join(Dir.mktmpdir, filename)
    File.binwrite(path, PNG)
    Attachment.create!(:container => issue, :author => User.find(1),
                       :file => Rack::Test::UploadedFile.new(path, 'image/png', true))
  end

  def deliver_to_supportclient(text)
    HelpdeskMailer.email_to_supportclient(
      @issue, :recipient => 'owner@example.com', :text => text
    ).deliver
  end

  def build_mail(issue, html)
    Mail.new do
      to 'owner@example.com'
      from 'redmine@mydomain.foo'
      subject 'test'
      header['X-Redmine-Issue-Id'] = issue.id.to_s
      header['X-Redmine-Project'] = issue.project.identifier
      text_part { body 'text' }
      html_part do
        content_type 'text/html; charset=UTF-8'
        body html
      end
    end
  end

  def assert_embedded(mail, attachment)
    inline = mail.attachments.select(&:inline?)
    assert_equal [attachment.filename], inline.map(&:filename)
    html = mail.html_part.decoded
    assert_include %(src="#{inline.first.url}"), html
    assert_not_include '/attachments/download/', html
    # survives encoding (what is actually sent)
    assert_include inline.first.content_id, mail.encoded
  end
end
